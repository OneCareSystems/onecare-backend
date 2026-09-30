package com.onecare.backend.controller;

import com.onecare.backend.entity.*;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.enums.Status;
import com.onecare.backend.repository.*;
import com.onecare.backend.security.RolePermission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional // every test rolls back, so the database stays clean
class PrescriptionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private MedicineRepository medicineRepository;

    @Autowired
    private PrescriptionRepository prescriptionRepository;

    @Autowired
    private PrescriptionItemRepository prescriptionItemRepository;

    @Autowired
    private AppointmentRepository appointmentRepository;

    /** Appointments use an assigned (non-generated) id in this codebase. */
    private static final AtomicLong APPOINTMENT_SEQ = new AtomicLong(900_000L);

    // ---------- helpers ----------

    /**
     * Builds a caller whose authorities come from the real RolePermission mapping.
     * The username must belong to a persisted user so the service can resolve the
     * authenticated doctor from the database.
     */
    private RequestPostProcessor as(String username, Role role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        RolePermission.getPermissions(role).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return user(username).authorities(authorities);
    }

    private User createUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("rx-" + role.name().toLowerCase() + "-" + unique);
        user.setEmail("rx-" + unique + "@onecare.test");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private Patient createPatient(boolean active) {
        Patient patient = new Patient();
        patient.setFullName("Test Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 5, 12));
        patient.setContactNo("0771234567");
        patient.setGender(Gender.MALE);
        patient.setIsActive(active);
        return patientRepository.save(patient);
    }

    private Appointment createAppointment(Patient patient, User doctor) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(APPOINTMENT_SEQ.incrementAndGet());
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now().plusDays(1));
        appointment.setTimeSlot(LocalTime.of(10, 0));
        appointment.setStatus(Status.CANCELLED);
        appointment.setReason("Checkup");
        return appointmentRepository.save(appointment);
    }

    private Medicine createMedicine(LocalDate expiryDate, boolean quarantined) {
        Medicine medicine = new Medicine();
        medicine.setName("Medicine " + UUID.randomUUID().toString().substring(0, 8));
        medicine.setPrice(BigDecimal.TEN);
        medicine.setUnitPrice(BigDecimal.TEN);
        medicine.setCategory("Analgesic");
        medicine.setUnit("Tablet");
        medicine.setReorderLevel(10);
        medicine.setStockQuantity(100);
        medicine.setExpiryDate(expiryDate);
        medicine.setIsQuarantined(quarantined);
        medicine.setCreatedAt(LocalDateTime.now());
        medicine.setUpdatedAt(LocalDateTime.now());
        return medicineRepository.save(medicine);
    }

    private Prescription savePrescription(PrescriptionStatus status, String clinicalNotes) {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);

        Prescription prescription = new Prescription();
        prescription.setDoctor(doctor);
        prescription.setPatient(patient);
        prescription.setAppointment(appointment);
        prescription.setDate(LocalDate.now());
        prescription.setStatus(status);
        prescription.setClinicalNotes(clinicalNotes);
        return prescriptionRepository.save(prescription);
    }

    private String body(Patient patient, Appointment appointment, String clinicalNotes, Medicine... medicines) {
        StringBuilder items = new StringBuilder();
        for (Medicine medicine : medicines) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("""
                    {"itemType":"IN_HOUSE","medicineId":%d,"dosage":"500mg","frequency":"TDS","durationDays":5,"quantity":10}"""
                    .formatted(medicine.getMedicineId()));
        }
        return """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"%s","items":[%s]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId(), clinicalNotes, items);
    }

    private Medicine validMedicine() {
        return createMedicine(LocalDate.now().plusYears(1), false);
    }

    // ---------- 3. Prescription creation ----------

    @Test
    void create_returns201_withIssuedStatusAndItems() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);
        Medicine med1 = validMedicine();
        Medicine med2 = validMedicine();

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(patient, appointment, "Severe headache", med1, med2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.prescriptionId").isNumber())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.patientId").value(patient.getPatientId().intValue()))
                .andExpect(jsonPath("$.data.doctorId").value(doctor.getUserId().intValue()))
                .andExpect(jsonPath("$.data.appointmentId").value(appointment.getAppointmentId().intValue()))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].medicineId").value(med1.getMedicineId().intValue()));

        assertEquals(1, prescriptionRepository.count());
        assertEquals(2, prescriptionItemRepository.count());
    }

    @Test
    void create_missingAppointment_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Medicine medicine = validMedicine();

        String body = """
                {"patientId":%d,"clinicalNotes":"Notes",
                 "items":[{"itemType":"IN_HOUSE","medicineId":%d,"dosage":"500mg","frequency":"TDS",
                           "durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), medicine.getMedicineId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[?(@.field == 'appointmentId')]").isNotEmpty());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_ignoresClientSuppliedStatusAndDoctorId() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        User otherDoctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);
        Medicine medicine = validMedicine();

        String body = """
                {"patientId":%d,"appointmentId":%d,"doctorId":%d,"status":"DISPENSED",
                 "clinicalNotes":"Notes",
                 "items":[{"itemType":"IN_HOUSE","medicineId":%d,"dosage":"500mg","frequency":"TDS",
                           "durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId(),
                        otherDoctor.getUserId(), medicine.getMedicineId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.doctorId").value(doctor.getUserId().intValue()));

        Prescription saved = prescriptionRepository.findAll().get(0);
        assertEquals(PrescriptionStatus.ISSUED, saved.getStatus());
        assertEquals(doctor.getUserId(), saved.getDoctor().getUserId());
    }

    @Test
    void create_asAdmin_returns403() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);
        Medicine medicine = validMedicine();

        mockMvc.perform(post("/api/prescriptions")
                        .with(as("admin-test", Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(patient, appointment, "Notes", medicine)))
                .andExpect(status().isForbidden());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_asSuperAdmin_returns403() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);
        Medicine medicine = validMedicine();

        mockMvc.perform(post("/api/prescriptions")
                        .with(as("superadmin-test", Role.SUPER_ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(patient, appointment, "Notes", medicine)))
                .andExpect(status().isForbidden());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_unknownPatient_returns404() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);
        Medicine medicine = validMedicine();

        String body = """
                {"patientId":999999,"appointmentId":%d,"clinicalNotes":"Notes",
                 "items":[{"itemType":"IN_HOUSE","medicineId":%d,"dosage":"500mg","frequency":"TDS",
                           "durationDays":5,"quantity":10}]}"""
                .formatted(appointment.getAppointmentId(), medicine.getMedicineId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_inactivePatient_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient inactive = createPatient(false);
        Appointment appointment = createAppointment(inactive, doctor);
        Medicine medicine = validMedicine();

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(inactive, appointment, "Notes", medicine)))
                .andExpect(status().isBadRequest());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_unknownMedicine_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);

        String body = """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"Notes",
                 "items":[{"itemType":"IN_HOUSE","medicineId":999999,"dosage":"500mg","frequency":"TDS",
                           "durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_quarantinedMedicine_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Medicine quarantined = createMedicine(LocalDate.now().plusYears(1), true);
        Appointment appointment = createAppointment(patient, doctor);

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(patient, appointment, "Notes", quarantined)))
                .andExpect(status().isBadRequest());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_expiredMedicine_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Medicine expired = createMedicine(LocalDate.now().minusDays(1), false);
        Appointment appointment = createAppointment(patient, doctor);

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(patient, appointment, "Notes", expired)))
                .andExpect(status().isBadRequest());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_unknownAppointment_returns404() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Medicine medicine = validMedicine();

        String body = """
                {"patientId":%d,"appointmentId":999999,"clinicalNotes":"Notes",
                 "items":[{"itemType":"IN_HOUSE","medicineId":%d,"dosage":"500mg","frequency":"TDS",
                           "durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), medicine.getMedicineId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_emptyItems_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);

        String body = """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"Notes","items":[]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        assertEquals(0, prescriptionRepository.count());
    }

    // ---------- 4 + 5. Transaction rollback ----------

    @Test
    void create_invalidMedicineInMiddleOfThree_rollsBack_zeroRowsRemain() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Medicine med1 = validMedicine();
        Medicine med2Expired = createMedicine(LocalDate.now().minusDays(1), false);
        Medicine med3 = validMedicine();
        Appointment appointment = createAppointment(patient, doctor);

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(patient, appointment, "Notes", med1, med2Expired, med3)))
                .andExpect(status().isBadRequest());

        assertEquals(0, prescriptionRepository.count());
        assertEquals(0, prescriptionItemRepository.count());
    }

    // ---------- IN_HOUSE vs EXTERNAL_PURCHASE items ----------

    @Test
    void create_externalOnly_returns201_withoutCatalogReference() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);

        String body = """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"Notes",
                 "items":[{"itemType":"EXTERNAL_PURCHASE","medicineName":"Imported Drug X",
                           "dosage":"500mg","frequency":"TDS","durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].itemType").value("EXTERNAL_PURCHASE"))
                .andExpect(jsonPath("$.data.items[0].medicineName").value("Imported Drug X"));

        assertEquals(1, prescriptionRepository.count());
        assertEquals(1, prescriptionItemRepository.count());
        assertEquals(null, prescriptionItemRepository.findAll().get(0).getMedicine());
    }

    @Test
    void create_mixedInHouseAndExternal_persistsAllItems() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Medicine med1 = validMedicine();
        Medicine med2 = validMedicine();
        Appointment appointment = createAppointment(patient, doctor);

        String body = """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"Notes",
                 "items":[
                    {"itemType":"IN_HOUSE","medicineId":%d,"dosage":"500mg","frequency":"TDS",
                     "durationDays":5,"quantity":10},
                    {"itemType":"IN_HOUSE","medicineId":%d,"dosage":"250mg","frequency":"BD",
                     "durationDays":7,"quantity":14},
                    {"itemType":"EXTERNAL_PURCHASE","medicineName":"Imported Drug X",
                     "dosage":"500mg","frequency":"TDS","durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId(),
                        med1.getMedicineId(), med2.getMedicineId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.items[0].itemType").value("IN_HOUSE"))
                .andExpect(jsonPath("$.data.items[1].itemType").value("IN_HOUSE"))
                .andExpect(jsonPath("$.data.items[2].itemType").value("EXTERNAL_PURCHASE"))
                .andExpect(jsonPath("$.data.items[2].medicineName").value("Imported Drug X"));

        assertEquals(3, prescriptionItemRepository.count());
    }

    @Test
    void create_externalWithMedicineId_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);

        String body = """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"Notes",
                 "items":[{"itemType":"EXTERNAL_PURCHASE","medicineId":1,
                           "medicineName":"Imported Drug X","dosage":"500mg","frequency":"TDS",
                           "durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.data[?(@.field == 'items[0].medicineReferenceValid')]").isNotEmpty());

        assertEquals(0, prescriptionRepository.count());
    }

    @Test
    void create_inHouseWithoutMedicineId_returns400() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient(true);
        Appointment appointment = createAppointment(patient, doctor);

        String body = """
                {"patientId":%d,"appointmentId":%d,"clinicalNotes":"Notes",
                 "items":[{"itemType":"IN_HOUSE",
                           "dosage":"500mg","frequency":"TDS","durationDays":5,"quantity":10}]}"""
                .formatted(patient.getPatientId(), appointment.getAppointmentId());

        mockMvc.perform(post("/api/prescriptions")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath(
                        "$.data[?(@.field == 'items[0].medicineReferenceValid')]").isNotEmpty());

        assertEquals(0, prescriptionRepository.count());
    }

    // ---------- 6. List ----------

    @Test
    void list_neverExposesClinicalNotes() throws Exception {
        savePrescription(PrescriptionStatus.ISSUED, "Highly sensitive clinical notes");

        mockMvc.perform(get("/api/prescriptions").with(as("rx-list", Role.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.data[0].clinicalNotes").doesNotExist());
    }

    @Test
    void list_statusFilter_returnsMatchingOnly() throws Exception {
        savePrescription(PrescriptionStatus.ISSUED, "notes");
        savePrescription(PrescriptionStatus.DISPENSED, "notes");

        mockMvc.perform(get("/api/prescriptions")
                        .param("status", "DISPENSED").with(as("rx-list2", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].status").value("DISPENSED"));
    }

    @Test
    void list_invalidStatusFilter_returns400() throws Exception {
        mockMvc.perform(get("/api/prescriptions")
                        .param("status", "BOGUS").with(as("rx-list3", Role.ADMIN)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 7. Detail + clinical notes ----------

    @Test
    void detail_asDoctor_includesClinicalNotes() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "Severe headache");

        mockMvc.perform(get("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-doc-detail", Role.DOCTOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prescriptionId").value(prescription.getPrescriptionId().intValue()))
                .andExpect(jsonPath("$.data.clinicalNotes").value("Severe headache"));
    }

    @Test
    void detail_asPharmacist_includesClinicalNotes() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "Severe headache");

        mockMvc.perform(get("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-pharma-detail", Role.PHARMACIST)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinicalNotes").value("Severe headache"));
    }

    @Test
    void detail_asAdmin_omitsClinicalNotes() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "Severe headache");

        mockMvc.perform(get("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-admin-detail", Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.prescriptionId").value(prescription.getPrescriptionId().intValue()))
                .andExpect(jsonPath("$.data.clinicalNotes").doesNotExist());
    }

    @Test
    void detail_asSuperAdmin_omitsClinicalNotes() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "Severe headache");

        mockMvc.perform(get("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-superadmin-detail", Role.SUPER_ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.clinicalNotes").doesNotExist());
    }

    @Test
    void detail_unknownPrescription_returns404() throws Exception {
        mockMvc.perform(get("/api/prescriptions/999999").with(as("rx-detail404", Role.DOCTOR)))
                .andExpect(status().isNotFound());
    }

    // ---------- 8. Cancellation ----------

    @Test
    void cancel_whileIssued_returns200_andBecomesCancelled() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "notes");
        User doctor = prescription.getDoctor();

        mockMvc.perform(delete("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        Prescription reloaded = prescriptionRepository
                .findById(prescription.getPrescriptionId()).orElseThrow();
        assertEquals(PrescriptionStatus.CANCELLED, reloaded.getStatus());
    }

    @Test
    void cancel_whileDispensed_returns400() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.DISPENSED, "notes");
        User doctor = prescription.getDoctor();

        mockMvc.perform(delete("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf()))
                .andExpect(status().isBadRequest());

        Prescription reloaded = prescriptionRepository
                .findById(prescription.getPrescriptionId()).orElseThrow();
        assertEquals(PrescriptionStatus.DISPENSED, reloaded.getStatus());
    }

    @Test
    void cancel_unknownPrescription_returns404() throws Exception {
        User doctor = createUser(Role.DOCTOR);

        mockMvc.perform(delete("/api/prescriptions/999999")
                        .with(as(doctor.getUsername(), Role.DOCTOR)).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancel_asPharmacist_returns403() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "notes");

        mockMvc.perform(delete("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-pharma-cancel", Role.PHARMACIST)).with(csrf()))
                .andExpect(status().isForbidden());

        Prescription reloaded = prescriptionRepository
                .findById(prescription.getPrescriptionId()).orElseThrow();
        assertEquals(PrescriptionStatus.ISSUED, reloaded.getStatus());
    }

    // ---------- PRESCRIPTION_UPDATE / PRESCRIPTION_STATUS_UPDATE matrix ----------

    @Test
    void update_asAdmin_returns403() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "notes");

        // Admin holds PRESCRIPTION_STATUS_UPDATE but NOT PRESCRIPTION_UPDATE
        mockMvc.perform(put("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-admin-update", Role.ADMIN)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void update_asPharmacist_returns403() throws Exception {
        Prescription prescription = savePrescription(PrescriptionStatus.ISSUED, "notes");

        mockMvc.perform(put("/api/prescriptions/" + prescription.getPrescriptionId())
                        .with(as("rx-pharma-update", Role.PHARMACIST)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }
}
