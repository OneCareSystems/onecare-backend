package com.onecare.backend.service;

import com.onecare.backend.entity.*;
import com.onecare.backend.enums.*;
import com.onecare.backend.exception.*;
import com.onecare.backend.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PrescriptionDispensingServiceTest {
    @Mock PrescriptionRepository prescriptions;
    @Mock MedicineRepository medicines;
    @Mock MedicineService medicineService;
    @Mock EntityManager entityManager;
    @InjectMocks PrescriptionDispensingService service;
    Prescription prescription;
    Medicine medicine;
    PrescriptionItem item;

    @BeforeEach void setup() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "entityManager", entityManager);
        prescription = new Prescription();
        prescription.setPrescriptionId(1L);
        prescription.setStatus(PrescriptionStatus.ISSUED);
        Patient patient = new Patient(); patient.setPatientId(2L); prescription.setPatient(patient);
        User doctor = new User(); doctor.setUserId(3L); prescription.setDoctor(doctor);
        medicine = new Medicine(); medicine.setMedicineId(4L); medicine.setStockQuantity(10);
        medicine.setPrice(BigDecimal.TEN); medicine.setExpiryDate(LocalDate.now().plusDays(1));
        item = new PrescriptionItem(); item.setItemId(5L); item.setPrescription(prescription);
        item.setItemType(PrescriptionItemType.IN_HOUSE); item.setMedicine(medicine); item.setQuantity(6);
        prescription.getItems().add(item);
    }

    void mutation() {
        when(prescriptions.findDispenseRevision(1L)).thenReturn(Optional.of(0L));
        when(prescriptions.findByIdForDispensing(1L)).thenReturn(Optional.of(prescription));
    }
    void stock() {
        when(medicines.findByIdForUpdate(4L)).thenReturn(Optional.of(medicine));
    }
    void save() { when(prescriptions.save(prescription)).thenReturn(prescription); }

    @Test void fullDispenseUsesStockDeltaAndSnapshot() {
        mutation(); stock(); save();
        service.dispense(1L, false);
        verify(medicineService).updateStock(4L, new com.onecare.backend.dto.request.StockUpdateRequest(-6));
        assertEquals(6, item.getQuantityDispensed());
        assertEquals(BigDecimal.TEN, item.getUnitPrice());
        assertEquals(PrescriptionStatus.DISPENSED, prescription.getStatus());
    }

    @ParameterizedTest @EnumSource(value = PrescriptionStatus.class, names = {"DISPENSED", "DISPENSED_EXTERNAL", "CANCELLED"})
    void invalidStatusDoesNotTouchStock(PrescriptionStatus status) {
        mutation(); prescription.setStatus(status);
        assertEquals("Prescription already dispensed", assertThrows(BusinessRuleException.class,
                () -> service.dispense(1L, false)).getMessage());
        verifyNoInteractions(medicines, medicineService);
    }

    @Test void shortfallRequiresConfirmation() {
        mutation(); stock(); medicine.setStockQuantity(2);
        StockShortfallException ex = assertThrows(StockShortfallException.class, () -> service.dispense(1L, false));
        assertEquals(4, ex.getPreview().items().getFirst().shortfallQuantity());
        assertEquals(0, item.getQuantityDispensed()); verifyNoInteractions(medicineService);
    }

    @Test void partialThenFollowUpNeverExceedsPrescribedAndKeepsPrice() {
        mutation(); stock(); save(); medicine.setStockQuantity(2);
        service.dispense(1L, true);
        assertEquals(2, item.getQuantityDispensed());
        assertEquals(PrescriptionStatus.PARTIALLY_DISPENSED, prescription.getStatus());
        when(prescriptions.findDispenseRevision(1L)).thenReturn(Optional.of(1L));
        medicine.setStockQuantity(100); medicine.setPrice(BigDecimal.ONE);
        service.dispense(1L, false);
        verify(medicineService).updateStock(4L, new com.onecare.backend.dto.request.StockUpdateRequest(-4));
        assertEquals(6, item.getQuantityDispensed()); assertEquals(BigDecimal.TEN, item.getUnitPrice());
        assertEquals(PrescriptionStatus.DISPENSED, prescription.getStatus());
    }

    @Test void quarantinePreviewAndDispenseBlocked() {
        medicine.setIsQuarantined(true); assertUnsafe("quarantined");
    }
    @Test void expiryPreviewAndDispenseBlocked() {
        medicine.setExpiryDate(LocalDate.now().minusDays(1)); assertUnsafe("expired");
    }
    void assertUnsafe(String reason) {
        when(prescriptions.findById(1L)).thenReturn(Optional.of(prescription));
        var preview = service.preview(1L).items().getFirst();
        assertFalse(preview.dispensable()); assertTrue(preview.reason().contains(reason));
        verifyNoInteractions(medicineService); mutation(); stock();
        assertThrows(BusinessRuleException.class, () -> service.dispense(1L, true));
        assertEquals(0, item.getQuantityDispensed()); verifyNoInteractions(medicineService);
    }

    @Test void overlappingRevisionIsConflict() {
        mutation(); prescription.setDispenseRevision(1);
        assertThrows(ConflictException.class, () -> service.dispense(1L, false));
        verifyNoInteractions(medicines, medicineService);
    }
    @Test void missingPrescriptionIsNotFound() {
        when(prescriptions.findDispenseRevision(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> service.dispense(1L, false));
        assertThrows(ResourceNotFoundException.class, () -> service.preview(1L));
    }
    @Test void repeatedMedicineLinesShareAvailability() {
        when(prescriptions.findById(1L)).thenReturn(Optional.of(prescription));
        PrescriptionItem second = new PrescriptionItem(); second.setItemId(6L); second.setMedicine(medicine);
        second.setQuantity(6); second.setItemType(PrescriptionItemType.IN_HOUSE);
        prescription.getItems().add(second);
        var preview = service.preview(1L);
        assertEquals(4, preview.items().get(1).availableStock());
        assertEquals(2, preview.items().get(1).shortfallQuantity());
        mutation(); stock(); save(); service.dispense(1L, true);
        assertEquals(6, item.getQuantityDispensed()); assertEquals(4, second.getQuantityDispensed());
    }
    @Test void externalPurchaseIsExcludedAndTodayExpiryIsAllowed() {
        mutation(); stock(); save(); medicine.setExpiryDate(LocalDate.now());
        PrescriptionItem external = new PrescriptionItem(); external.setItemId(6L); external.setQuantity(10);
        external.setItemType(PrescriptionItemType.EXTERNAL_PURCHASE); prescription.getItems().add(external);
        service.dispense(1L, false);
        assertEquals(0, external.getQuantityDispensed()); assertEquals(PrescriptionStatus.DISPENSED, prescription.getStatus());
    }
    @Test void zeroStockCanRemainPartialWithoutMutation() {
        mutation(); stock(); save(); medicine.setStockQuantity(0); service.dispense(1L, true);
        verifyNoInteractions(medicineService); assertEquals(0, item.getQuantityDispensed());
        assertEquals(PrescriptionStatus.PARTIALLY_DISPENSED, prescription.getStatus());
    }
}
