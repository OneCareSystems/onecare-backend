package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreatePrescriptionRequest;
import com.onecare.backend.dto.request.PrescriptionItemRequest;
import com.onecare.backend.dto.request.UpdatePrescriptionRequest;
import com.onecare.backend.dto.response.PrescriptionDetailResponse;
import com.onecare.backend.dto.response.PrescriptionResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.PrescriptionItemType;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.MedicineRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.Permission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrescriptionServiceImplTest {

    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MedicineRepository medicineRepository;
    @Mock
    private AppointmentRepository appointmentRepository;

    @InjectMocks
    private PrescriptionServiceImpl service;

    private User doctorUser;

    @BeforeEach
    void setUp() {
        doctorUser = new User();
        doctorUser.setUserId(10L);
        doctorUser.setUsername("dr.test");
        doctorUser.setEmail("dr.test@onecare.test");
        doctorUser.setPasswordHash("hash");
        doctorUser.setRole(Role.DOCTOR);
        doctorUser.setIsActive(true);
        doctorUser.setFailedAttempts(0);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** The MockMvc-style principal carries only a username, so the service falls back to findByUsername. */
    private void authenticate(String username, String... authorities) {
        List<GrantedAuthority> granted = new java.util.ArrayList<>();
        for (String authority : authorities) {
            granted.add(new SimpleGrantedAuthority(authority));
        }
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("x")
                .authorities(authorities)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, granted));
    }

    private void stubAuthenticatedDoctor() {
        when(userRepository.findByUsername("dr.test")).thenReturn(Optional.of(doctorUser));
    }

    private Patient activePatient(Long id) {
        Patient patient = new Patient();
        patient.setPatientId(id);
        patient.setFullName("Kasun Fernando");
        patient.setIsActive(true);
        return patient;
    }

    /** The default appointmentId used by {@link #request(Long, PrescriptionItemRequest...)}. */
    private static final long APPOINTMENT_ID = 7L;

    private Appointment appointment() {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(APPOINTMENT_ID);
        appointment.setPatient(activePatient(5L));
        appointment.setDoctor(doctorUser);
        return appointment;
    }

    private void stubAppointmentExists() {
        when(appointmentRepository.findById(APPOINTMENT_ID))
                .thenReturn(Optional.of(appointment()));
    }

    private Medicine medicine(long id, LocalDate expiry, boolean quarantined) {
        Medicine m = new Medicine();
        m.setMedicineId(id);
        m.setName("Medicine " + id);
        m.setExpiryDate(expiry);
        m.setIsQuarantined(quarantined);
        return m;
    }

    private PrescriptionItemRequest item(long medicineId) {
        return new PrescriptionItemRequest(
                PrescriptionItemType.IN_HOUSE, medicineId, null, "500mg", "TDS", 5, 10);
    }

    private PrescriptionItemRequest externalItem(String medicineName) {
        return new PrescriptionItemRequest(
                PrescriptionItemType.EXTERNAL_PURCHASE, null, medicineName, "500mg", "TDS", 5, 10);
    }

    private CreatePrescriptionRequest request(Long patientId, PrescriptionItemRequest... items) {
        return new CreatePrescriptionRequest(patientId, APPOINTMENT_ID, "Severe headache", List.of(items));
    }

    private CreatePrescriptionRequest requestWithoutAppointment(Long patientId, PrescriptionItemRequest... items) {
        return new CreatePrescriptionRequest(patientId, null, "Severe headache", List.of(items));
    }

    private UpdatePrescriptionRequest updateRequest(String clinicalNotes, PrescriptionItemRequest... items) {
        return new UpdatePrescriptionRequest(clinicalNotes, List.of(items));
    }

    private void stubSaveAssignsId(long id) {
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(invocation -> {
            Prescription p = invocation.getArgument(0);
            p.setPrescriptionId(id);
            p.setCreatedAt(LocalDateTime.now());
            p.setUpdatedAt(LocalDateTime.now());
            return p;
        });
    }

    private Prescription savedPrescription(PrescriptionStatus status, String notes) {
        Prescription prescription = new Prescription();
        prescription.setPrescriptionId(1L);
        prescription.setDoctor(doctorUser);
        prescription.setPatient(activePatient(5L));
        prescription.setAppointment(appointment());
        prescription.setDate(LocalDate.now());
        prescription.setStatus(status);
        prescription.setClinicalNotes(notes);
        prescription.setCreatedAt(LocalDateTime.now());
        prescription.setUpdatedAt(LocalDateTime.now());
        return prescription;
    }

    // ---------- Creation: doctor validation ----------

    @Test
    void createPrescription_persistedDoctorWithDoctorRole_succeeds() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), false)));
        stubSaveAssignsId(101L);

        PrescriptionResponse response = service.createPrescription(request(5L, item(1L)));

        ArgumentCaptor<Prescription> captor = ArgumentCaptor.forClass(Prescription.class);
        verify(prescriptionRepository).save(captor.capture());
        Prescription saved = captor.getValue();

        assertEquals(PrescriptionStatus.ISSUED, saved.getStatus());
        assertEquals(doctorUser, saved.getDoctor());
        assertEquals(5L, saved.getPatient().getPatientId());
        assertEquals(APPOINTMENT_ID, saved.getAppointment().getAppointmentId());
        assertEquals(1, saved.getItems().size());
        assertEquals(LocalDate.now(), saved.getDate());
        assertEquals(101L, response.prescriptionId());
        assertEquals(PrescriptionStatus.ISSUED, response.status());
        assertEquals("Severe headache", saved.getClinicalNotes());
    }

    @Test
    void createPrescription_persistedUserWithoutDoctorRole_rejected() {
        authenticate("dr.test", "ROLE_DOCTOR");
        User pharmacist = new User();
        pharmacist.setUserId(11L);
        pharmacist.setUsername("dr.test");
        pharmacist.setRole(Role.PHARMACIST);
        when(userRepository.findByUsername("dr.test")).thenReturn(Optional.of(pharmacist));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.createPrescription(request(5L, item(1L))));

        assertTrue(exception.getMessage().contains("DOCTOR"));
        verify(prescriptionRepository, never()).save(any());
    }

    // ---------- Creation: patient / medicine validation ----------

    @Test
    void createPrescription_invalidPatientNotFound_rejected() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createPrescription(request(99L, item(1L))));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_inactivePatient_rejected() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Patient inactive = activePatient(5L);
        inactive.setIsActive(false);
        when(patientRepository.findById(5L)).thenReturn(Optional.of(inactive));

        assertThrows(BusinessRuleException.class,
                () -> service.createPrescription(request(5L, item(1L))));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_missingAppointment_rejected_nothingSaved() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.createPrescription(requestWithoutAppointment(5L, item(1L))));

        assertTrue(exception.getMessage().contains("appointmentId"));
        verify(appointmentRepository, never()).findById(any());
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_unknownAppointment_rejected_nothingSaved() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.createPrescription(request(5L, item(1L))));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_unknownMedicine_rejected_nothingSaved() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of()); // medicine 1 missing

        assertThrows(BusinessRuleException.class,
                () -> service.createPrescription(request(5L, item(1L))));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_quarantinedMedicine_rejected_nothingSaved() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), true)));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.createPrescription(request(5L, item(1L))));

        assertTrue(exception.getMessage().contains("quarantined"));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_expiredMedicine_rejected_nothingSaved() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().minusDays(1), false)));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.createPrescription(request(5L, item(1L))));

        assertTrue(exception.getMessage().contains("expired"));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void createPrescription_invalidMedicineInMiddleOfThree_rejectsEntirePrescription() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        // item 2 (medicine 2) is expired — items 1 and 3 are fine
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), false),
                medicine(2L, LocalDate.now().minusDays(1), false),
                medicine(3L, LocalDate.now().plusYears(1), false)));

        assertThrows(BusinessRuleException.class, () -> service.createPrescription(
                request(5L, item(1L), item(2L), item(3L))));

        verify(prescriptionRepository, never()).save(any());
    }

    // ---------- IN_HOUSE vs EXTERNAL_PURCHASE ----------

    @Test
    void createPrescription_externalOnly_skipsMedicineLookup_andPersists() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        stubSaveAssignsId(101L);

        PrescriptionResponse response = service.createPrescription(
                request(5L, externalItem("Imported Drug X")));

        assertEquals(1, response.items().size());
        verify(medicineRepository, never()).findAllById(anyList());

        ArgumentCaptor<Prescription> captor = ArgumentCaptor.forClass(Prescription.class);
        verify(prescriptionRepository).save(captor.capture());
        PrescriptionItem savedItem = captor.getValue().getItems().get(0);
        assertEquals(PrescriptionItemType.EXTERNAL_PURCHASE, savedItem.getItemType());
        assertEquals("Imported Drug X", savedItem.getMedicineName());
        assertNull(savedItem.getMedicine());
    }

    @Test
    void createPrescription_mixedInHouseAndExternal_persistsBothTypes() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), false),
                medicine(2L, LocalDate.now().plusYears(1), false)));
        stubSaveAssignsId(101L);

        service.createPrescription(request(5L,
                item(1L), item(2L), externalItem("Imported Drug X")));

        ArgumentCaptor<Prescription> captor = ArgumentCaptor.forClass(Prescription.class);
        verify(prescriptionRepository).save(captor.capture());
        List<PrescriptionItem> items = captor.getValue().getItems();

        assertEquals(3, items.size());
        assertEquals(PrescriptionItemType.IN_HOUSE, items.get(0).getItemType());
        assertEquals(1L, items.get(0).getMedicine().getMedicineId());
        assertEquals(PrescriptionItemType.IN_HOUSE, items.get(1).getItemType());
        assertEquals(2L, items.get(1).getMedicine().getMedicineId());
        assertEquals(PrescriptionItemType.EXTERNAL_PURCHASE, items.get(2).getItemType());
        assertEquals("Imported Drug X", items.get(2).getMedicineName());
        assertNull(items.get(2).getMedicine());
    }

    @Test
    void createPrescription_unknownMedicineAmongMixedItems_rejected_nothingSaved() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(patientRepository.findById(5L)).thenReturn(Optional.of(activePatient(5L)));
        stubAppointmentExists();
        // only medicine 1 is known — the unknown IN_HOUSE id still fails
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), false)));

        assertThrows(BusinessRuleException.class, () -> service.createPrescription(
                request(5L, item(1L), item(999L), externalItem("Imported Drug X"))));

        verify(prescriptionRepository, never()).save(any());
    }

    // ---------- Update flow (PUT /{id}) ----------

    @Test
    void updatePrescription_issuedBySameDoctor_replacesContentAndItems() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Prescription prescription = savedPrescription(PrescriptionStatus.ISSUED, "old notes");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), false)));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(i -> i.getArgument(0));

        PrescriptionResponse response = service.updatePrescription(
                1L, updateRequest("new notes", item(1L)));

        assertEquals("new notes", prescription.getClinicalNotes());
        assertEquals(PrescriptionStatus.ISSUED, prescription.getStatus());
        assertEquals(doctorUser, prescription.getDoctor());
        assertEquals(1, prescription.getItems().size());
        assertEquals(1, response.items().size());
        assertEquals(PrescriptionStatus.ISSUED, response.status());
        verify(prescriptionRepository).save(prescription);
    }

    @Test
    void updatePrescription_nullClinicalNotes_keepsExistingNotes() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Prescription prescription = savedPrescription(PrescriptionStatus.ISSUED, "keep me");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of(
                medicine(1L, LocalDate.now().plusYears(1), false)));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrescription(1L, updateRequest(null, item(1L)));

        assertEquals("keep me", prescription.getClinicalNotes());
        assertEquals(1, prescription.getItems().size());
    }

    @Test
    void updatePrescription_externalOnlyItem_skipsMedicineLookup() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Prescription prescription = savedPrescription(PrescriptionStatus.ISSUED, "notes");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(i -> i.getArgument(0));

        service.updatePrescription(1L, updateRequest("notes", externalItem("Imported Drug X")));

        verify(medicineRepository, never()).findAllById(anyList());
        assertEquals(1, prescription.getItems().size());
        assertEquals(PrescriptionItemType.EXTERNAL_PURCHASE,
                prescription.getItems().get(0).getItemType());
    }

    @Test
    void updatePrescription_dispensed_rejected_nothingChanged() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Prescription prescription = savedPrescription(PrescriptionStatus.DISPENSED, "notes");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.updatePrescription(1L, updateRequest("new notes", item(1L))));

        assertTrue(exception.getMessage().contains("ISSUED"));
        assertEquals("notes", prescription.getClinicalNotes());
        assertTrue(prescription.getItems().isEmpty());
        verify(medicineRepository, never()).findAllById(anyList());
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void updatePrescription_cancelled_rejected_nothingChanged() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Prescription prescription = savedPrescription(PrescriptionStatus.CANCELLED, "notes");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));

        assertThrows(BusinessRuleException.class,
                () -> service.updatePrescription(1L, updateRequest("new notes", item(1L))));

        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void updatePrescription_anotherDoctorsPrescription_rejected_nothingChanged() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        User otherDoctor = new User();
        otherDoctor.setUserId(99L);
        otherDoctor.setUsername("other.doctor");
        otherDoctor.setRole(Role.DOCTOR);
        Prescription prescription = savedPrescription(PrescriptionStatus.ISSUED, "notes");
        prescription.setDoctor(otherDoctor);
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.updatePrescription(1L, updateRequest("Hijacked", item(1L))));

        assertTrue(exception.getMessage().contains("another doctor"));
        assertEquals("notes", prescription.getClinicalNotes());
        verify(medicineRepository, never()).findAllById(anyList());
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void updatePrescription_unknownPrescription_rejected() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        when(prescriptionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.updatePrescription(99L, updateRequest("new notes", item(1L))));

        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void updatePrescription_invalidMedicine_rejected_prescriptionUnchanged() {
        authenticate("dr.test", "ROLE_DOCTOR");
        stubAuthenticatedDoctor();
        Prescription prescription = savedPrescription(PrescriptionStatus.ISSUED, "old notes");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));
        when(medicineRepository.findAllById(anyList())).thenReturn(List.of()); // medicine missing

        assertThrows(BusinessRuleException.class,
                () -> service.updatePrescription(1L, updateRequest("new notes", item(1L))));

        assertEquals("old notes", prescription.getClinicalNotes());
        assertTrue(prescription.getItems().isEmpty());
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void updatePrescription_nonDoctor_rejected() {
        authenticate("dr.test", "ROLE_DOCTOR");
        User pharmacist = new User();
        pharmacist.setUserId(11L);
        pharmacist.setUsername("dr.test");
        pharmacist.setRole(Role.PHARMACIST);
        when(userRepository.findByUsername("dr.test")).thenReturn(Optional.of(pharmacist));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.updatePrescription(1L, updateRequest("new notes", item(1L))));

        assertTrue(exception.getMessage().contains("DOCTOR"));
        verify(prescriptionRepository, never()).findById(any());
        verify(prescriptionRepository, never()).save(any());
    }

    // ---------- Status lifecycle ----------

    @Test
    void cancel_issuedPrescription_becomesCancelled() {
        Prescription prescription = savedPrescription(PrescriptionStatus.ISSUED, "notes");
        when(prescriptionRepository.findById(1L)).thenReturn(Optional.of(prescription));
        when(prescriptionRepository.save(any(Prescription.class))).thenAnswer(i -> i.getArgument(0));

        service.cancelPrescription(1L);

        assertEquals(PrescriptionStatus.CANCELLED, prescription.getStatus());
    }

    @Test
    void cancel_dispensedPrescription_rejected() {
        when(prescriptionRepository.findById(1L))
                .thenReturn(Optional.of(savedPrescription(PrescriptionStatus.DISPENSED, "notes")));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.cancelPrescription(1L));

        assertTrue(exception.getMessage().contains("Dispensed"));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void cancel_alreadyCancelled_rejected() {
        when(prescriptionRepository.findById(1L))
                .thenReturn(Optional.of(savedPrescription(PrescriptionStatus.CANCELLED, "notes")));

        assertThrows(BusinessRuleException.class, () -> service.cancelPrescription(1L));
        verify(prescriptionRepository, never()).save(any());
    }

    @Test
    void cancel_unknownPrescription_rejected() {
        when(prescriptionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.cancelPrescription(99L));
    }

    // ---------- Clinical notes visibility ----------

    @Test
    void detail_withClinicalNotesPermission_includesNotes() {
        authenticate("dr.test", "ROLE_DOCTOR", Permission.PRESCRIPTION_READ_CLINICAL_NOTES);
        when(prescriptionRepository.findById(1L))
                .thenReturn(Optional.of(savedPrescription(PrescriptionStatus.ISSUED, "Severe headache")));

        PrescriptionDetailResponse response = service.findPrescriptionById(1L);

        assertEquals("Severe headache", response.clinicalNotes());
    }

    @Test
    void detail_withoutClinicalNotesPermission_omitsNotes() {
        authenticate("admin", "ROLE_ADMIN");
        when(prescriptionRepository.findById(1L))
                .thenReturn(Optional.of(savedPrescription(PrescriptionStatus.ISSUED, "Severe headache")));

        PrescriptionDetailResponse response = service.findPrescriptionById(1L);

        assertNull(response.clinicalNotes());
    }

    @Test
    void detail_unknownPrescription_rejected() {
        authenticate("dr.test", "ROLE_DOCTOR");
        when(prescriptionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.findPrescriptionById(99L));
    }

    // ---------- List ----------

    @Test
    void list_withoutFilter_returnsAll() {
        when(prescriptionRepository.findAll()).thenReturn(List.of(
                savedPrescription(PrescriptionStatus.ISSUED, "notes")));

        List<PrescriptionResponse> response = service.findAllPrescriptions(null);

        assertEquals(1, response.size());
        assertEquals(PrescriptionStatus.ISSUED, response.get(0).status());
        assertNotNull(response.get(0).items());
        verify(prescriptionRepository).findAll();
    }

    @Test
    void list_withStatusFilter_filtersByStatus() {
        when(prescriptionRepository.findByStatus(PrescriptionStatus.DISPENSED))
                .thenReturn(List.of(savedPrescription(PrescriptionStatus.DISPENSED, "notes")));

        List<PrescriptionResponse> response = service.findAllPrescriptions("DISPENSED");

        assertEquals(1, response.size());
        assertEquals(PrescriptionStatus.DISPENSED, response.get(0).status());
    }

    @Test
    void list_withInvalidStatus_rejected() {
        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> service.findAllPrescriptions("BOGUS"));

        assertTrue(exception.getMessage().contains("Invalid status filter"));
        verify(prescriptionRepository, never()).findAll();
    }
}
