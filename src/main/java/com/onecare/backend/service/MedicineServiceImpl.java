package com.onecare.backend.service;

import com.onecare.backend.dto.request.MedicineRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.response.MedicineResponse;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.exception.InvalidStockException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.MedicineRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
public class MedicineServiceImpl implements MedicineService {

    private static final int NEAR_EXPIRY_DAYS = 30;

    private final MedicineRepository medicineRepository;

    public MedicineServiceImpl(MedicineRepository medicineRepository) {
        this.medicineRepository = medicineRepository;
    }

    @Override
    @Transactional
    public MedicineResponse createMedicine(MedicineRequest request) {

        Medicine medicine = new Medicine();

        medicine.setName(request.name());
        medicine.setGenericName(request.genericName());
        medicine.setCategory(request.category());
        medicine.setUnit(request.unit());
        medicine.setPrice(request.price());
        medicine.setUnitPrice(request.unitPrice());
        medicine.setExpiryDate(request.expiryDate());
        medicine.setReorderLevel(request.reorderLevel());

        // Stock is initially zero.
        // Stock changes must be done through the stock update API.
        medicine.setStockQuantity(0);

        medicine.setIsQuarantined(
                request.isQuarantined() != null
                        ? request.isQuarantined()
                        : false
        );

        LocalDateTime now = LocalDateTime.now();
        medicine.setCreatedAt(now);
        medicine.setUpdatedAt(now);

        Medicine savedMedicine = medicineRepository.save(medicine);

        log.info(
                "Medicine created | medicineId={} | name={}",
                savedMedicine.getMedicineId(),
                savedMedicine.getName()
        );

        return mapToResponse(savedMedicine);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MedicineResponse> findAllMedicines() {

        return medicineRepository.findAll()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public MedicineResponse findMedicineById(Long id) {

        Medicine medicine = findMedicine(id);

        checkAndQuarantineIfExpired(medicine);

        return mapToResponse(medicine);
    }

    @Override
    @Transactional
    public MedicineResponse updateMedicine(
            Long id,
            MedicineRequest request
    ) {

        Medicine medicine = findMedicine(id);

        medicine.setName(request.name());
        medicine.setGenericName(request.genericName());
        medicine.setCategory(request.category());
        medicine.setUnit(request.unit());
        medicine.setPrice(request.price());
        medicine.setUnitPrice(request.unitPrice());
        medicine.setExpiryDate(request.expiryDate());
        medicine.setReorderLevel(request.reorderLevel());

        if (request.isQuarantined() != null) {
            medicine.setIsQuarantined(request.isQuarantined());
        }

        /*
         * Stock quantity is intentionally NOT updated here.
         *
         * Stock can only be changed through:
         * PATCH /api/medicines/{id}/stock
         */

        medicine.setUpdatedAt(LocalDateTime.now());

        Medicine updatedMedicine = medicineRepository.save(medicine);

        log.info(
                "Medicine updated | medicineId={}",
                updatedMedicine.getMedicineId()
        );

        return mapToResponse(updatedMedicine);
    }

    @Override
    @Transactional
    public void deleteMedicine(Long id) {

        Medicine medicine = findMedicine(id);

        medicineRepository.delete(medicine);

        log.info(
                "Medicine deleted | medicineId={}",
                id
        );
    }

    @Override
    @Transactional
    public MedicineResponse updateStock(
            Long id,
            StockUpdateRequest request
    ) {

        Medicine medicine = findMedicine(id);

        checkAndQuarantineIfExpired(medicine);

        int currentStock = medicine.getStockQuantity();
        int delta = request.delta();

        /*
         * Use long for the calculation to avoid integer overflow.
         */
        long newStock = (long) currentStock + delta;

        if (newStock < 0) {

            log.warn(
                    "Stock update rejected | medicineId={} | currentStock={} | delta={}",
                    id,
                    currentStock,
                    delta
            );

            throw new InvalidStockException(
                    "Stock quantity cannot be negative"
            );
        }

        medicine.setStockQuantity((int) newStock);
        medicine.setUpdatedAt(LocalDateTime.now());

        Medicine updatedMedicine = medicineRepository.save(medicine);

        log.info(
                "Stock updated | medicineId={} | previousStock={} | delta={} | newStock={}",
                id,
                currentStock,
                delta,
                newStock
        );


        return mapToResponse(updatedMedicine);
    }

    @Override
    @Transactional
    public List<MedicineResponse> findLowStockMedicines() {

        quarantineExpiredMedicines();

        return medicineRepository.findLowStockMedicines()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<MedicineResponse> findNearExpiryMedicines() {

        quarantineExpiredMedicines();

        LocalDate today = LocalDate.now();
        LocalDate nearExpiryDate = today.plusDays(NEAR_EXPIRY_DAYS);

        return medicineRepository
                .findNearExpiryMedicines(today, nearExpiryDate)
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Transactional
    public List<MedicineResponse> findAvailableMedicines() {

        quarantineExpiredMedicines();

        return medicineRepository.findByIsQuarantinedFalse()
                .stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Override
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void quarantineExpiredMedicines() {

        LocalDate today = LocalDate.now();

        List<Medicine> expiredMedicines =
                medicineRepository
                        .findByExpiryDateBeforeAndIsQuarantinedFalse(today);

        for (Medicine medicine : expiredMedicines) {

            medicine.setIsQuarantined(true);
            medicine.setUpdatedAt(LocalDateTime.now());

            log.info(
                    "Medicine quarantined due to expiry | medicineId={} | expiryDate={}",
                    medicine.getMedicineId(),
                    medicine.getExpiryDate()
            );
        }

        if (!expiredMedicines.isEmpty()) {
            medicineRepository.saveAll(expiredMedicines);
        }
    }

    private Medicine findMedicine(Long id) {

        return medicineRepository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "Medicine not found with id: " + id
                        )
                );
    }

    private void checkAndQuarantineIfExpired(Medicine medicine) {

        LocalDate today = LocalDate.now();

        if (medicine.getExpiryDate().isBefore(today)
                && !Boolean.TRUE.equals(medicine.getIsQuarantined())) {

            medicine.setIsQuarantined(true);
            medicine.setUpdatedAt(LocalDateTime.now());

            medicineRepository.save(medicine);

            log.info(
                    "Medicine quarantined during read due to expiry | medicineId={} | expiryDate={}",
                    medicine.getMedicineId(),
                    medicine.getExpiryDate()
            );
        }
    }

    private MedicineResponse mapToResponse(Medicine medicine) {

        return new MedicineResponse(
                medicine.getMedicineId(),
                medicine.getName(),
                medicine.getGenericName(),
                medicine.getCategory(),
                medicine.getUnit(),
                medicine.getPrice(),
                medicine.getStockQuantity(),
                medicine.getExpiryDate(),
                medicine.getReorderLevel(),
                medicine.getUnitPrice(),
                medicine.getIsQuarantined(),
                medicine.getCreatedAt(),
                medicine.getUpdatedAt()
        );
    }
}