package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateExternalDispensingRequest;
import com.onecare.backend.dto.request.ExternalDispensingItemRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.request.VerifyExternalDispensingRequest;
import com.onecare.backend.dto.response.ExternalDispensingResponse;
import com.onecare.backend.dto.response.MedicineResponse;
import com.onecare.backend.entity.*;
import com.onecare.backend.enums.*;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.repository.*;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalDispensingServiceImplTest {

    @Mock
    private ExternalDispensingRepository externalDispensingRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private MedicineRepository medicineRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private MedicineService medicineService;

    @InjectMocks
    private ExternalDispensingServiceImpl service;

    @Test
    void create_andCompleteExternalDispensing_usesPendingThenVerifiedFlow() {
        Patient patient = new Patient();
        patient.setPatientId(7L);
        patient.setIsActive(true);

        Prescription prescription = new Prescription();
        prescription.setPrescriptionId(11L);
        prescription.setPatient(patient);
        prescription.setStatus(PrescriptionStatus.ISSUED);
        prescription.setItems(List.of());

        Medicine medicine = new Medicine();
        medicine.setMedicineId(99L);
        medicine.setName("Paracetamol");
        medicine.setStockQuantity(12);
        medicine.setExpiryDate(LocalDate.now().plusDays(30));

        ExternalDispensing pending = new ExternalDispensing();
        pending.setDispenseId(23L);
        pending.setPrescription(prescription);
        pending.setPatientId(7L);
        pending.setVerificationMethod("ID_CARD");
        pending.setVerificationStatus("PENDING");
        pending.setStatus(Status.PENDING);
        pending.setDeliveryMethod(DeliveryMethod.PICK_UP);
        pending.setDispenseDate(LocalDateTime.now());
        pending.setDispensedAt(LocalDateTime.now());

        DispensingItem pendingItem = new DispensingItem();
        pendingItem.setDispensing(pending);
        pendingItem.setMedicine(medicine);
        pendingItem.setQuantityDispensed(3);
        pending.setItems(new java.util.ArrayList<>(List.of(pendingItem)));

        when(patientRepository.findById(7L)).thenReturn(Optional.of(patient));
        when(prescriptionRepository.findById(11L)).thenReturn(Optional.of(prescription));
        when(medicineRepository.findById(99L)).thenReturn(Optional.of(medicine));
        when(externalDispensingRepository.save(any(ExternalDispensing.class))).thenAnswer(invocation -> {
            ExternalDispensing arg = invocation.getArgument(0);
            if (arg.getDispenseId() == null) {
                arg.setDispenseId(23L);
            }
            return arg;
        });

        CreateExternalDispensingRequest request = new CreateExternalDispensingRequest(
                11L,
                7L,
                "ID_CARD",
                "PICK_UP",
                List.of(new ExternalDispensingItemRequest(99L, 3)));

        ExternalDispensingResponse created = service.createExternalDispensing(request);
        assertNotNull(created);
        assertEquals(Status.PENDING, created.status());
        assertEquals("PENDING", created.verificationStatus());

        when(externalDispensingRepository.findById(23L)).thenReturn(Optional.of(pending));
        when(externalDispensingRepository.save(any(ExternalDispensing.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExternalDispensingResponse verified = service.verifyExternalDispensing(23L,
                new VerifyExternalDispensingRequest("ID_CARD"));
        assertEquals("VERIFIED", verified.verificationStatus());

        when(medicineService.updateStock(eq(99L), any(StockUpdateRequest.class)))
                .thenReturn(new MedicineResponse(99L, "Paracetamol", null, "Pain relief", "tablet",
                        new BigDecimal("2.50"), 9, LocalDate.now().plusDays(30), 5, false,
                        LocalDateTime.now(), LocalDateTime.now()));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExternalDispensingResponse completed = service.completeExternalDispensing(23L);
        assertEquals(Status.DISPENSED, completed.status());
        assertEquals("VERIFIED", completed.verificationStatus());
    }

    @Test
    void completeExternalDispensing_requiresVerification() {
        ExternalDispensing event = new ExternalDispensing();
        event.setDispenseId(2L);
        event.setStatus(Status.PENDING);
        event.setVerificationStatus("PENDING");
        event.setItems(new java.util.ArrayList<>());

        when(externalDispensingRepository.findById(2L)).thenReturn(Optional.of(event));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> service.completeExternalDispensing(2L));
        assertTrue(ex.getMessage().contains("verified"));
    }
}
