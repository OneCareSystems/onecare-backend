package com.onecare.backend.service;

import com.onecare.backend.dto.request.MedicineRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.response.MedicineResponse;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.exception.InvalidStockException;
import com.onecare.backend.repository.MedicineRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MedicineServiceImplTest {

    @Mock
    private MedicineRepository medicineRepository;

    @InjectMocks
    private MedicineServiceImpl medicineService;

    private Medicine medicine;

    @BeforeEach
    void setUp() {

        medicine = new Medicine();

        medicine.setMedicineId(1L);
        medicine.setName("Paracetamol");
        medicine.setGenericName("Paracetamol");
        medicine.setCategory("Painkiller");
        medicine.setUnit("Tablet");
        medicine.setPrice(new BigDecimal("10.00"));
        medicine.setStockQuantity(10);
        medicine.setExpiryDate(LocalDate.now().plusMonths(6));
        medicine.setReorderLevel(5);
        medicine.setIsQuarantined(false);
        medicine.setCreatedAt(LocalDateTime.now());
        medicine.setUpdatedAt(LocalDateTime.now());
    }

    // =========================================================
    // 1. POSITIVE STOCK DELTA
    // =========================================================

    @Test
    void updateStock_positiveDelta_updatesStock() {

        // Given
        StockUpdateRequest request = new StockUpdateRequest(5);

        when(medicineRepository.findById(1L))
                .thenReturn(Optional.of(medicine));

        when(medicineRepository.save(any(Medicine.class)))
                .thenReturn(medicine);

        // When
     
                medicineService.updateStock(1L, request);

        // Then
        assertEquals(15, medicine.getStockQuantity());

        verify(medicineRepository).findById(1L);
        verify(medicineRepository).save(medicine);
    }

    // =========================================================
    // 2. NEGATIVE STOCK DELTA
    // =========================================================

    @Test
    void updateStock_negativeDelta_updatesStock() {

        // Given
        StockUpdateRequest request = new StockUpdateRequest(-3);

        when(medicineRepository.findById(1L))
                .thenReturn(Optional.of(medicine));

        when(medicineRepository.save(any(Medicine.class)))
                .thenReturn(medicine);

                medicineService.updateStock(1L, request);

        // Then
        assertEquals(7, medicine.getStockQuantity());

        verify(medicineRepository).findById(1L);
        verify(medicineRepository).save(medicine);
    }

    // =========================================================
    // 3. NEGATIVE RESULTING STOCK
    // =========================================================

    @Test
    void updateStock_resultingNegative_throwsExceptionAndStockRemainsUnchanged() {

        // Given
        medicine.setStockQuantity(5);

        StockUpdateRequest request = new StockUpdateRequest(-10);

        when(medicineRepository.findById(1L))
                .thenReturn(Optional.of(medicine));

        // When + Then
        assertThrows(
                InvalidStockException.class,
                () -> medicineService.updateStock(1L, request)
        );

        // Stock must remain unchanged
        assertEquals(5, medicine.getStockQuantity());

        // Medicine must NOT be saved
        verify(medicineRepository, never())
                .save(any(Medicine.class));
    }

    // =========================================================
    // 4. PUT UPDATE MUST NOT CHANGE STOCK
    // =========================================================

    @Test
    void updateMedicine_doesNotChangeStock() {

        // Given
        medicine.setStockQuantity(50);

        MedicineRequest request = new MedicineRequest(
                "Updated Paracetamol",
                "Paracetamol",
                "Painkiller",
                "Tablet",
                new BigDecimal("12.00"),
                LocalDate.now().plusMonths(8),
                10,
                false
        );

        when(medicineRepository.findById(1L))
                .thenReturn(Optional.of(medicine));

        when(medicineRepository.save(any(Medicine.class)))
                .thenReturn(medicine);

        
                medicineService.updateMedicine(1L, request);

        // Then
        assertEquals(50, medicine.getStockQuantity());

        assertEquals(
                "Updated Paracetamol",
                medicine.getName()
        );

        assertEquals(
                new BigDecimal("12.00"),
                medicine.getPrice()
        );

        verify(medicineRepository).save(medicine);
    }

    // =========================================================
    // 5. EXPIRED MEDICINE IS QUARANTINED
    // =========================================================

    @Test
    void quarantineExpiredMedicines_marksExpiredMedicineAsQuarantined() {

        // Given
        medicine.setExpiryDate(LocalDate.now().minusDays(1));
        medicine.setIsQuarantined(false);

        when(
                medicineRepository
                        .findByExpiryDateBeforeAndIsQuarantinedFalse(
                                any(LocalDate.class)
                        )
        ).thenReturn(List.of(medicine));

        // When
        medicineService.quarantineExpiredMedicines();

        // Then
        assertTrue(medicine.getIsQuarantined());

        verify(medicineRepository)
                .saveAll(anyList());
    }

    // =========================================================
    // 6. ALREADY QUARANTINED MEDICINES ARE NOT INCLUDED
    // =========================================================

    @Test
    void findAvailableMedicines_excludesQuarantinedMedicines() {

        // Given
        Medicine availableMedicine = createMedicine(
                1L,
                "Paracetamol",
                false
        );

        when(
                medicineRepository
                        .findByExpiryDateBeforeAndIsQuarantinedFalse(
                                any(LocalDate.class)
                        )
        ).thenReturn(List.of());

        when(
                medicineRepository
                        .findByIsQuarantinedFalse()
        ).thenReturn(List.of(availableMedicine));

        // When
        List<MedicineResponse> result =
                medicineService.findAvailableMedicines();

        // Then
        assertEquals(1, result.size());

        verify(medicineRepository)
                .findByIsQuarantinedFalse();
    }

    // =========================================================
    // 7. LOW STOCK MEDICINES
    // =========================================================

    @Test
    void findLowStockMedicines_returnsLowStockMedicines() {

        // Given
        medicine.setStockQuantity(3);
        medicine.setReorderLevel(5);
        medicine.setIsQuarantined(false);

        when(
                medicineRepository
                        .findByExpiryDateBeforeAndIsQuarantinedFalse(
                                any(LocalDate.class)
                        )
        ).thenReturn(List.of());

        when(
                medicineRepository
                        .findLowStockMedicines()
        ).thenReturn(List.of(medicine));

        // When
        List<MedicineResponse> result =
                medicineService.findLowStockMedicines();

        // Then
        assertEquals(1, result.size());

        assertEquals(
                3,
                result.get(0).stockQuantity()
        );

        verify(medicineRepository)
                .findLowStockMedicines();
    }

    // =========================================================
    // 8. NEAR EXPIRY MEDICINES
    // =========================================================

    @Test
    void findNearExpiryMedicines_returnsNearExpiryMedicines() {

        // Given
        medicine.setExpiryDate(
                LocalDate.now().plusDays(15)
        );

        medicine.setIsQuarantined(false);

        when(
                medicineRepository
                        .findByExpiryDateBeforeAndIsQuarantinedFalse(
                                any(LocalDate.class)
                        )
        ).thenReturn(List.of());

        when(
                medicineRepository.findNearExpiryMedicines(
                        any(LocalDate.class),
                        any(LocalDate.class)
                )
        ).thenReturn(List.of(medicine));

        // When
        List<MedicineResponse> result =
                medicineService.findNearExpiryMedicines();

        // Then
        assertEquals(1, result.size());

        assertEquals(
                medicine.getMedicineId(),
                result.get(0).medicineId()
        );

        verify(medicineRepository)
                .findNearExpiryMedicines(
                        any(LocalDate.class),
                        any(LocalDate.class)
                );
    }

    // =========================================================
    // 9. AVAILABLE MEDICINES DO NOT RETURN QUARANTINED DATA
    // =========================================================

    @Test
    void findAvailableMedicines_doesNotReturnQuarantinedMedicine() {

        // Given
        Medicine availableMedicine =
                createMedicine(
                        1L,
                        "Paracetamol",
                        false
                );

        
                createMedicine(
                        2L,
                        "Expired Medicine",
                        true
                );

        when(
                medicineRepository
                        .findByExpiryDateBeforeAndIsQuarantinedFalse(
                                any(LocalDate.class)
                        )
        ).thenReturn(List.of());

        when(
                medicineRepository
                        .findByIsQuarantinedFalse()
        ).thenReturn(List.of(availableMedicine));

        // When
        List<MedicineResponse> result =
                medicineService.findAvailableMedicines();

        // Then
        assertEquals(1, result.size());

        assertEquals(
                1L,
                result.get(0).medicineId()
        );

        assertNotEquals(
                2L,
                result.get(0).medicineId()
        );
    }

    // =========================================================
    // HELPER METHOD
    // =========================================================

    private Medicine createMedicine(
            Long id,
            String name,
            Boolean quarantined
    ) {

        Medicine medicine = new Medicine();

        medicine.setMedicineId(id);
        medicine.setName(name);
        medicine.setGenericName(name);
        medicine.setCategory("Painkiller");
        medicine.setUnit("Tablet");
        medicine.setPrice(new BigDecimal("10.00"));
        medicine.setStockQuantity(10);
        medicine.setExpiryDate(
                LocalDate.now().plusMonths(6)
        );
        medicine.setReorderLevel(5);
        medicine.setIsQuarantined(quarantined);
        medicine.setCreatedAt(LocalDateTime.now());
        medicine.setUpdatedAt(LocalDateTime.now());

        return medicine;
    }
}