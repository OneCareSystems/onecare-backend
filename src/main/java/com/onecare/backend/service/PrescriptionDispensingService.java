package com.onecare.backend.service;

import com.onecare.backend.audit.Audited;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.response.DispensePreviewResponse;
import com.onecare.backend.dto.response.PrescriptionResponse;
import com.onecare.backend.entity.*;
import com.onecare.backend.enums.PrescriptionItemType;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.exception.*;
import com.onecare.backend.repository.MedicineRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class PrescriptionDispensingService {
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private final PrescriptionRepository prescriptions;
    private final MedicineRepository medicines;
    private final MedicineService medicineService;

    public PrescriptionDispensingService(PrescriptionRepository prescriptions,
            MedicineRepository medicines, MedicineService medicineService) {
        this.prescriptions = prescriptions;
        this.medicines = medicines;
        this.medicineService = medicineService;
    }

    @Transactional(readOnly = true)
    public DispensePreviewResponse preview(Long id) {
        Prescription prescription = prescriptions.findById(id).orElseThrow(() -> notFound(id));
        PrescriptionServiceImpl.checkViewAccess(prescription);
        return buildPreview(prescription);
    }

    @Transactional
    @Audited(action = "PRESCRIPTION_DISPENSE", module = "PRESCRIPTION", entityIdParameter = "id")
    public PrescriptionResponse dispense(Long id, boolean confirmPartial) {
        // Scalar read does not populate the persistence context. After waiting for
        // the row lock we can distinguish an overlapping request from a later retry.
        Long revision = prescriptions.findDispenseRevision(id).orElseThrow(() -> notFound(id));
        Prescription prescription = prescriptions.findByIdForDispensing(id).orElseThrow(() -> notFound(id));
        if (revision != prescription.getDispenseRevision()) {
            throw new ConflictException("Prescription was dispensed by another request. Please refresh.");
        }
        if (prescription.getStatus() != PrescriptionStatus.ISSUED
                && prescription.getStatus() != PrescriptionStatus.PARTIALLY_DISPENSED) {
            throw new BusinessRuleException("Prescription already dispensed");
        }

        // Lock distinct medicines in stable order, including shared lines, before
        // calculating availability. This also serializes different prescriptions.
        Map<Long, Medicine> locked = new HashMap<>();
        prescription.getItems().stream().filter(this::inHouse)
                .map(PrescriptionItem::getMedicine).filter(Objects::nonNull)
                .map(Medicine::getMedicineId).distinct().sorted().forEach(medicineId ->
                    locked.put(medicineId, medicines.findByIdForUpdate(medicineId)
                            .orElseThrow(() -> new ResourceNotFoundException("Medicine not found with id: " + medicineId))));
        for (PrescriptionItem item : prescription.getItems()) {
            if (inHouse(item) && item.getMedicine() != null) {
                item.setMedicine(locked.get(item.getMedicine().getMedicineId()));
            }
        }
        locked.values().forEach(medicine -> entityManager.refresh(medicine, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE));
        DispensePreviewResponse preview = buildPreview(prescription);
        // Unsafe medicines block the operation even when partial dispensing is confirmed.
        for (PrescriptionItem item : prescription.getItems()) {
            if (inHouse(item) && remaining(item) > 0) {
                String reason = DispensingRules.unavailableReason(item.getMedicine());
                if (reason != null) throw new BusinessRuleException(reason);
            }
        }
        if (!confirmPartial && prescription.getItems().stream().filter(this::inHouse)
                .anyMatch(item -> preview.items().get(prescription.getItems().indexOf(item)).shortfallQuantity() > 0)) {
            throw new StockShortfallException(preview);
        }

        for (int index = 0; index < prescription.getItems().size(); index++) {
            PrescriptionItem item = prescription.getItems().get(index);
            if (!inHouse(item)) continue;
            DispensePreviewResponse.Item planned = preview.items().get(index);
            int quantity = DispensingRules.quantityToDispense(remaining(item), planned.availableStock());
            if (quantity > 0) {
                medicineService.updateStock(item.getMedicine().getMedicineId(), new StockUpdateRequest(-quantity));
                // Freeze the price on first handover; follow-ups use the same price.
                if (item.getUnitPrice() == null) item.setUnitPrice(item.getMedicine().getPrice());
                item.setQuantityDispensed(item.getQuantityDispensed() + quantity);
            }
        }
        boolean remaining = prescription.getItems().stream().filter(this::inHouse).anyMatch(i -> remaining(i) > 0);
        prescription.setStatus(remaining ? PrescriptionStatus.PARTIALLY_DISPENSED : PrescriptionStatus.DISPENSED);
        prescription.setDispenseRevision(prescription.getDispenseRevision() + 1);
        return PrescriptionResponse.from(prescriptions.save(prescription));
    }

    private DispensePreviewResponse buildPreview(Prescription prescription) {
        Map<Long, Integer> available = new HashMap<>();
        List<DispensePreviewResponse.Item> items = new ArrayList<>();
        for (PrescriptionItem item : prescription.getItems()) {
            Medicine medicine = item.getMedicine();
            int remaining = remaining(item);
            String reason = inHouse(item) ? DispensingRules.unavailableReason(medicine) : "External purchase item";
            int stock = medicine == null ? 0 : available.computeIfAbsent(medicine.getMedicineId(),
                    key -> Math.max(0, medicine.getStockQuantity()));
            int quantity = reason == null ? DispensingRules.quantityToDispense(remaining, stock) : 0;
            int shortfall = remaining - quantity;
            if (medicine != null && reason == null) available.put(medicine.getMedicineId(), stock - quantity);
            if (reason == null && shortfall > 0) reason = "Insufficient stock";
            items.add(new DispensePreviewResponse.Item(item.getItemId(), medicine == null ? null : medicine.getMedicineId(),
                    item.getQuantity(), item.getQuantityDispensed(), remaining, stock,
                    item.getUnitPrice() != null ? item.getUnitPrice() : medicine == null ? null : medicine.getPrice(),
                    reason == null && remaining > 0, shortfall, reason));
        }
        return new DispensePreviewResponse(prescription.getPrescriptionId(), prescription.getPatient().getPatientId(),
                prescription.getStatus(), items);
    }

    private boolean inHouse(PrescriptionItem item) { return item.getItemType() == PrescriptionItemType.IN_HOUSE; }
    private int remaining(PrescriptionItem item) { return Math.max(0, item.getQuantity() - item.getQuantityDispensed()); }
    private ResourceNotFoundException notFound(Long id) { return new ResourceNotFoundException("Prescription not found with id: " + id); }
}
