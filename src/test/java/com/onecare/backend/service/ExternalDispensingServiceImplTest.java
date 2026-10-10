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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

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

    /**
     * Existing flow test:
     * create -> verify -> complete
     */
    @Test
    void create_andCompleteExternalDispensing_usesPendingThenVerifiedFlow() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.ISSUED);

        Medicine medicine = createMedicine(99L, 12);

        ExternalDispensing pending = createVerifiedReadyEvent(
                23L,
                prescription,
                medicine,
                3,
                "PHONE");

        /*
         * For this test the event initially needs to behave as PENDING,
         * because identity verification happens after creation.
         */
        pending.setVerificationStatus("PENDING");
        pending.setVerifiedAt(null);

        when(patientRepository.findById(7L))
                .thenReturn(Optional.of(patient));

        when(prescriptionRepository.findById(11L))
                .thenReturn(Optional.of(prescription));

        when(medicineRepository.findById(99L))
                .thenReturn(Optional.of(medicine));

        when(externalDispensingRepository.save(any(ExternalDispensing.class)))
                .thenAnswer(invocation -> {
                    ExternalDispensing event = invocation.getArgument(0);

                    if (event.getDispenseId() == null) {
                        event.setDispenseId(23L);
                    }

                    return event;
                });

        CreateExternalDispensingRequest request = new CreateExternalDispensingRequest(
                11L,
                7L,
                "PHONE",
                "PICK_UP",
                List.of(
                        new ExternalDispensingItemRequest(
                                99L,
                                3)));

        ExternalDispensingResponse created = service.createExternalDispensing(request);

        assertNotNull(created);
        assertEquals(Status.PENDING, created.status());
        assertEquals(
                "PENDING",
                created.verificationStatus());

        when(externalDispensingRepository.findById(23L))
                .thenReturn(Optional.of(pending));

        ExternalDispensingResponse verified = service.verifyExternalDispensing(
                23L,
                new VerifyExternalDispensingRequest("PHONE"));

        assertEquals(
                "VERIFIED",
                verified.verificationStatus());

        when(medicineService.updateStock(
                eq(99L),
                any(StockUpdateRequest.class))).thenReturn(
                        medicineResponse(
                                99L,
                                9));

        when(prescriptionRepository.save(any(Prescription.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExternalDispensingResponse completed = service.completeExternalDispensing(23L);

        assertEquals(
                Status.DISPENSED,
                completed.status());

        assertEquals(
                "VERIFIED",
                completed.verificationStatus());
    }

    /**
     * Existing negative flow:
     * dispensing cannot complete before identity verification.
     */
    @Test
    void completeExternalDispensing_requiresVerification() {

        ExternalDispensing event = new ExternalDispensing();

        event.setDispenseId(2L);
        event.setStatus(Status.PENDING);
        event.setVerificationStatus("PENDING");
        event.setItems(new ArrayList<>());

        when(externalDispensingRepository.findById(2L))
                .thenReturn(Optional.of(event));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service
                        .completeExternalDispensing(2L));

        assertTrue(
                exception.getMessage()
                        .toLowerCase()
                        .contains("verified"));
    }

    /**
     * AC1
     *
     * Given sufficient stock,
     * completing external dispensing must:
     * - deduct the requested quantity
     * - mark external dispensing DISPENSED
     * - mark prescription DISPENSED
     */
    @Test
    void completeExternalDispensing_shouldDispenseFullQuantityWhenStockIsSufficient() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.ISSUED);

        /*
         * Stock = 12
         * Requested = 3
         *
         * Enough stock available.
         */
        Medicine medicine = createMedicine(99L, 12);

        ExternalDispensing event = createVerifiedReadyEvent(
                23L,
                prescription,
                medicine,
                3,
                "PHONE");

        when(externalDispensingRepository.findById(23L))
                .thenReturn(Optional.of(event));

        when(externalDispensingRepository.save(
                any(ExternalDispensing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(prescriptionRepository.save(
                any(Prescription.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(medicineService.updateStock(
                eq(99L),
                any(StockUpdateRequest.class))).thenReturn(
                        medicineResponse(
                                99L,
                                9));

        ExternalDispensingResponse response = service.completeExternalDispensing(23L);

        assertEquals(
                Status.DISPENSED,
                response.status());

        assertEquals(
                PrescriptionStatus.DISPENSED,
                prescription.getStatus());

        assertEquals(
                3,
                event.getItems()
                        .get(0)
                        .getQuantityDispensed());

        /*
         * DDP-21 stock service must be reused.
         */
        verify(
                medicineService,
                times(1)).updateStock(
                        eq(99L),
                        any(StockUpdateRequest.class));

        verify(
                prescriptionRepository,
                times(1)).save(prescription);
    }

    /**
     * AC2
     *
     * A DISPENSED prescription cannot be marked
     * for external dispensing.
     */
    @Test
    void markPrescriptionExternal_shouldRejectDispensedPrescription() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.DISPENSED);

        when(prescriptionRepository.findById(11L))
                .thenReturn(Optional.of(prescription));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service
                        .markPrescriptionExternal(11L));

        assertTrue(
                exception.getMessage()
                        .contains("ISSUED"));

        /*
         * Important:
         * rejected request must not create
         * ExternalDispensing row.
         */
        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));

        assertEquals(
                PrescriptionStatus.DISPENSED,
                prescription.getStatus());
    }

    /**
     * Extra AC2 guard:
     * CANCELLED prescriptions also cannot
     * be marked external.
     */
    @Test
    void markPrescriptionExternal_shouldRejectCancelledPrescription() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.CANCELLED);

        when(prescriptionRepository.findById(11L))
                .thenReturn(Optional.of(prescription));

        assertThrows(
                BusinessRuleException.class,
                () -> service
                        .markPrescriptionExternal(11L));

        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));
    }

    /**
     * Positive mark-external test.
     *
     * ISSUED prescription must be accepted.
     */
    @Test
    void markPrescriptionExternal_shouldAllowIssuedPrescription() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.ISSUED);

        when(prescriptionRepository.findById(11L))
                .thenReturn(Optional.of(prescription));

        when(externalDispensingRepository.save(
                any(ExternalDispensing.class))).thenAnswer(invocation -> {

                    ExternalDispensing event = invocation.getArgument(0);

                    event.setDispenseId(23L);

                    return event;
                });

        ExternalDispensingResponse response = service.markPrescriptionExternal(11L);

        assertNotNull(response);

        assertEquals(
                23L,
                response.dispenseId());

        assertEquals(
                11L,
                response.prescriptionId());

        assertEquals(
                Status.PENDING,
                response.status());

        verify(
                externalDispensingRepository,
                times(1)).save(any(ExternalDispensing.class));
    }

    /**
     * AC3
     *
     * Requested = 5
     * Available = 2
     *
     * Expected:
     * - only 2 dispensed
     * - external event PARTIALLY_DISPENSED
     * - prescription must NOT become DISPENSED
     */
    @Test
    void completeExternalDispensing_shouldPartiallyDispenseWhenStockIsInsufficient() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.ISSUED);

        Medicine medicine = createMedicine(
                99L,
                2);

        ExternalDispensing event = createVerifiedReadyEvent(
                23L,
                prescription,
                medicine,
                5,
                "PHONE");

        when(externalDispensingRepository.findById(23L))
                .thenReturn(Optional.of(event));

        when(externalDispensingRepository.save(
                any(ExternalDispensing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        /*
         * Available stock is only 2,
         * so DDP-21 receives a safe stock reduction.
         */
        when(medicineService.updateStock(
                eq(99L),
                any(StockUpdateRequest.class))).thenReturn(
                        medicineResponse(
                                99L,
                                0));

        ExternalDispensingResponse response = service.completeExternalDispensing(23L);

        assertEquals(
                Status.PARTIALLY_DISPENSED,
                response.status());

        assertEquals(
                2,
                event.getItems()
                        .get(0)
                        .getQuantityDispensed());

        /*
         * Prescription is not fully dispensed,
         * therefore it must remain ISSUED.
         */
        assertEquals(
                PrescriptionStatus.ISSUED,
                prescription.getStatus());

        verify(
                medicineService,
                times(1)).updateStock(
                        eq(99L),
                        any(StockUpdateRequest.class));

        /*
         * Partial fulfilment must not mark
         * the entire prescription as DISPENSED.
         */
        verify(
                prescriptionRepository,
                never()).save(prescription);
    }

    /**
     * Identity method 1:
     * PHONE
     */
    @Test
    void verifyExternalDispensing_shouldSupportPhoneVerification() {

        ExternalDispensing event = createPendingVerificationEvent(
                101L,
                "PHONE");

        when(externalDispensingRepository.findById(101L))
                .thenReturn(Optional.of(event));

        when(externalDispensingRepository.save(
                any(ExternalDispensing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExternalDispensingResponse response = service.verifyExternalDispensing(
                101L,
                new VerifyExternalDispensingRequest(
                        "PHONE"));

        assertEquals(
                "VERIFIED",
                response.verificationStatus());

        assertEquals(
                "PHONE",
                response.verificationMethod());

        assertNotNull(
                response.verifiedAt());
    }

    /**
     * Identity method 2:
     * CLINIC_ID
     */
    @Test
    void verifyExternalDispensing_shouldSupportClinicIdVerification() {

        ExternalDispensing event = createPendingVerificationEvent(
                102L,
                "CLINIC_ID");

        when(externalDispensingRepository.findById(102L))
                .thenReturn(Optional.of(event));

        when(externalDispensingRepository.save(
                any(ExternalDispensing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExternalDispensingResponse response = service.verifyExternalDispensing(
                102L,
                new VerifyExternalDispensingRequest(
                        "CLINIC_ID"));

        assertEquals(
                "VERIFIED",
                response.verificationStatus());

        assertEquals(
                "CLINIC_ID",
                response.verificationMethod());

        assertNotNull(
                response.verifiedAt());
    }

    /**
     * Identity method 3:
     * QR
     */
    @Test
    void verifyExternalDispensing_shouldSupportQrVerification() {

        ExternalDispensing event = createPendingVerificationEvent(
                103L,
                "QR");

        when(externalDispensingRepository.findById(103L))
                .thenReturn(Optional.of(event));

        when(externalDispensingRepository.save(
                any(ExternalDispensing.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ExternalDispensingResponse response = service.verifyExternalDispensing(
                103L,
                new VerifyExternalDispensingRequest(
                        "QR"));

        assertEquals(
                "VERIFIED",
                response.verificationStatus());

        assertEquals(
                "QR",
                response.verificationMethod());

        assertNotNull(
                response.verifiedAt());
    }

    /**
     * Completed events cannot be completed twice.
     */
    @Test
    void completeExternalDispensing_shouldRejectAlreadyDispensedEvent() {

        ExternalDispensing event = new ExternalDispensing();

        event.setDispenseId(23L);
        event.setStatus(Status.DISPENSED);
        event.setVerificationStatus("VERIFIED");
        event.setItems(new ArrayList<>());

        when(externalDispensingRepository.findById(23L))
                .thenReturn(Optional.of(event));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service
                        .completeExternalDispensing(23L));

        assertTrue(
                exception.getMessage()
                        .toLowerCase()
                        .contains("already"));
    }

    @Test
    void findAllExternalDispensing_withoutStatus_returnsAllEvents() {

        ExternalDispensing first = createPendingVerificationEvent(201L, "PHONE");

        ExternalDispensing second = createPendingVerificationEvent(202L, "QR");

        second.setStatus(Status.PARTIALLY_DISPENSED);

        when(externalDispensingRepository.findAll())
                .thenReturn(List.of(first, second));

        List<ExternalDispensingResponse> responses = service.findAllExternalDispensing(null);

        assertEquals(2, responses.size());

        verify(externalDispensingRepository, times(1))
                .findAll();
    }

    @Test
    void findAllExternalDispensing_withPartialStatus_returnsOnlyPartialEvents() {

        ExternalDispensing pending = createPendingVerificationEvent(201L, "PHONE");

        ExternalDispensing partial = createPendingVerificationEvent(202L, "QR");

        partial.setStatus(Status.PARTIALLY_DISPENSED);

        when(externalDispensingRepository.findAll())
                .thenReturn(List.of(pending, partial));

        List<ExternalDispensingResponse> responses = service.findAllExternalDispensing("PARTIALLY_DISPENSED");

        assertEquals(1, responses.size());

        assertEquals(
                Status.PARTIALLY_DISPENSED,
                responses.get(0).status());
    }

    @Test
    void findAllExternalDispensing_withInvalidStatus_throwsBusinessRuleException() {

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service.findAllExternalDispensing("INVALID_STATUS"));

        assertTrue(
                exception.getMessage()
                        .contains("PARTIALLY_DISPENSED"));
    }

    @Test
    void findExternalDispensingById_whenExists_returnsEvent() {

        ExternalDispensing event = createPendingVerificationEvent(301L, "PHONE");

        when(externalDispensingRepository.findById(301L))
                .thenReturn(Optional.of(event));

        ExternalDispensingResponse response = service.findExternalDispensingById(301L);

        assertNotNull(response);

        assertEquals(
                301L,
                response.dispenseId());
    }

    @Test
    void findExternalDispensingById_whenMissing_throwsException() {

        when(externalDispensingRepository.findById(999L))
                .thenReturn(Optional.empty());

        assertThrows(
                RuntimeException.class,
                () -> service.findExternalDispensingById(999L));
    }

    @Test
    void createExternalDispensing_whenRequestIsNull_throwsBusinessRuleException() {

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service.createExternalDispensing(null));

        assertTrue(
                exception.getMessage()
                        .contains("required"));

        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));
    }

    @Test
    void createExternalDispensing_whenQuantityIsZero_throwsBusinessRuleException() {

        Patient patient = createPatient(7L);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.ISSUED);

        when(prescriptionRepository.findById(11L))
                .thenReturn(Optional.of(prescription));

        when(patientRepository.findById(7L))
                .thenReturn(Optional.of(patient));

        CreateExternalDispensingRequest request = new CreateExternalDispensingRequest(
                11L,
                7L,
                "PHONE",
                "PICK_UP",
                List.of(
                        new ExternalDispensingItemRequest(
                                99L,
                                0)));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service.createExternalDispensing(request));

        assertTrue(
                exception.getMessage()
                        .contains("greater than zero"));

        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));
    }

    @Test
    void createExternalDispensing_whenPatientInactive_throwsBusinessRuleException() {

        Patient patient = createPatient(7L);

        patient.setIsActive(false);

        Prescription prescription = createPrescription(
                11L,
                patient,
                PrescriptionStatus.ISSUED);

        when(prescriptionRepository.findById(11L))
                .thenReturn(Optional.of(prescription));

        when(patientRepository.findById(7L))
                .thenReturn(Optional.of(patient));

        CreateExternalDispensingRequest request = new CreateExternalDispensingRequest(
                11L,
                7L,
                "PHONE",
                "PICK_UP",
                List.of(
                        new ExternalDispensingItemRequest(
                                99L,
                                3)));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service.createExternalDispensing(request));

        assertTrue(
                exception.getMessage()
                        .contains("not active"));

        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));
    }

    @Test
    void verifyExternalDispensing_whenCancelled_throwsBusinessRuleException() {

        ExternalDispensing event = createPendingVerificationEvent(
                401L,
                "PHONE");

        event.setStatus(Status.CANCELLED);

        when(externalDispensingRepository.findById(401L))
                .thenReturn(Optional.of(event));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service.verifyExternalDispensing(
                        401L,
                        new VerifyExternalDispensingRequest("PHONE")));

        assertTrue(
                exception.getMessage()
                        .contains("pending"));

        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));
    }

    @Test
    void verifyExternalDispensing_whenAlreadyDispensed_throwsBusinessRuleException() {

        ExternalDispensing event = createPendingVerificationEvent(
                402L,
                "CLINIC_ID");

        event.setStatus(Status.DISPENSED);

        when(externalDispensingRepository.findById(402L))
                .thenReturn(Optional.of(event));

        assertThrows(
                BusinessRuleException.class,
                () -> service.verifyExternalDispensing(
                        402L,
                        new VerifyExternalDispensingRequest("CLINIC_ID")));

        verify(
                externalDispensingRepository,
                never()).save(any(ExternalDispensing.class));
    }

    @Test
    void completeExternalDispensing_whenCancelled_throwsBusinessRuleException() {

        ExternalDispensing event = createVerifiedReadyEvent(
                501L,
                null,
                createMedicine(99L, 10),
                2,
                "PHONE");

        event.setStatus(Status.CANCELLED);

        when(externalDispensingRepository.findById(501L))
                .thenReturn(Optional.of(event));

        BusinessRuleException exception = assertThrows(
                BusinessRuleException.class,
                () -> service.completeExternalDispensing(501L));

        assertTrue(
                exception.getMessage()
                        .toLowerCase()
                        .contains("cancelled"));

        verify(
                medicineService,
                never()).updateStock(
                        any(),
                        any(StockUpdateRequest.class));
    }

    /*
     * ---------------------------------------------------------
     * Test helper methods
     * ---------------------------------------------------------
     */

    private Patient createPatient(Long patientId) {

        Patient patient = new Patient();

        patient.setPatientId(patientId);
        patient.setIsActive(true);

        return patient;
    }

    private Prescription createPrescription(
            Long prescriptionId,
            Patient patient,
            PrescriptionStatus status) {

        Prescription prescription = new Prescription();

        prescription.setPrescriptionId(
                prescriptionId);

        prescription.setPatient(patient);

        prescription.setStatus(status);

        prescription.setItems(
                new ArrayList<>());

        return prescription;
    }

    private Medicine createMedicine(
            Long medicineId,
            int stockQuantity) {

        Medicine medicine = new Medicine();

        medicine.setMedicineId(medicineId);

        medicine.setName(
                "Paracetamol");

        medicine.setStockQuantity(
                stockQuantity);

        medicine.setExpiryDate(
                LocalDate.now()
                        .plusDays(30));

        return medicine;
    }

    private ExternalDispensing createVerifiedReadyEvent(
            Long dispenseId,
            Prescription prescription,
            Medicine medicine,
            int quantity,
            String verificationMethod) {

        ExternalDispensing event = new ExternalDispensing();

        event.setDispenseId(
                dispenseId);

        event.setPrescription(
                prescription);

        if (prescription != null
                && prescription.getPatient() != null) {

            event.setPatientId(
                    prescription
                            .getPatient()
                            .getPatientId());
        }

        event.setVerificationMethod(
                verificationMethod);

        event.setVerificationStatus(
                "VERIFIED");

        event.setVerifiedAt(
                LocalDateTime.now());

        event.setRetryCount(1);

        event.setStatus(
                Status.PENDING);

        event.setDeliveryMethod(
                DeliveryMethod.PICK_UP);

        event.setDispenseDate(
                LocalDateTime.now());

        event.setDispensedAt(
                LocalDateTime.now());

        DispensingItem item = new DispensingItem();

        item.setDispensing(event);

        item.setMedicine(
                medicine);

        item.setQuantityDispensed(
                quantity);

        event.setItems(
                new ArrayList<>(
                        List.of(item)));

        return event;
    }

    private ExternalDispensing createPendingVerificationEvent(
            Long dispenseId,
            String verificationMethod) {

        ExternalDispensing event = new ExternalDispensing();

        event.setDispenseId(
                dispenseId);

        event.setVerificationMethod(
                verificationMethod);

        event.setVerificationStatus(
                "PENDING");

        event.setRetryCount(0);

        event.setStatus(
                Status.PENDING);

        event.setDeliveryMethod(
                DeliveryMethod.PICK_UP);

        event.setDispenseDate(
                LocalDateTime.now());

        event.setDispensedAt(
                LocalDateTime.now());

        event.setItems(
                new ArrayList<>());

        return event;
    }

    private MedicineResponse medicineResponse(
            Long medicineId,
            int stockQuantity) {

        return new MedicineResponse(
                medicineId,
                "Paracetamol",
                null,
                "Pain relief",
                "tablet",
                new BigDecimal("2.50"),
                stockQuantity,
                LocalDate.now()
                        .plusDays(30),
                5,
                false,
                LocalDateTime.now(),
                LocalDateTime.now());
    }
}