package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.DispensingItem;
import com.onecare.backend.entity.ExternalDispensing;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.DeliveryMethod;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.PrescriptionItemType;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.enums.Status;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.ExternalDispensingRepository;
import com.onecare.backend.repository.MedicineRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionItemRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.RolePermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reports end-to-end coverage: controller -> service -> repository on the test
 * database (AC2, AC3, AC4, AC5, AC6, AC7 plus clinic/pharmacy happy paths).
 * AC1 performance is covered by {@link ReportPerformanceIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // every test rolls back, so the database stays clean
class ReportApiIntegrationTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 12, 31); // exactly 365 days: allowed
    private static final String PATIENT_A_CONTACT = "0771234567";
    /** Stored canonically as +94771234567 (Patient @PrePersist), then masked. */
    private static final String PATIENT_A_MASKED = "+94*******67";

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private MedicineRepository medicineRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private PrescriptionRepository prescriptionRepository;
    @Autowired
    private PrescriptionItemRepository prescriptionItemRepository;
    @Autowired
    private ExternalDispensingRepository externalDispensingRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private User admin;
    private User superAdmin;
    private User doctor;
    private User otherDoctor;
    private User pharmacist;
    private Patient patientA;
    private Patient patientB;
    private Medicine paracetamol;
    private Medicine amoxicillin;
    private Medicine vitamin;

    @BeforeEach
    void seed() {
        admin = createUser(Role.ADMIN);
        superAdmin = createUser(Role.SUPER_ADMIN);
        doctor = createUser(Role.DOCTOR);
        otherDoctor = createUser(Role.DOCTOR);
        pharmacist = createUser(Role.PHARMACIST);

        patientA = createPatient("Patient A", PATIENT_A_CONTACT);
        patientB = createPatient("Patient B", "0719876543");

        paracetamol = createMedicine("Paracetamol 500mg", LocalDate.of(2024, 6, 15), false);
        amoxicillin = createMedicine("Amoxicillin 500mg", LocalDate.of(2024, 8, 20), true);
        vitamin = createMedicine("Vitamin C 1000mg", LocalDate.of(2025, 6, 1), false);

        // Clinic side: 9 appointments inside the range, 1 outside (excluded)
        createAppointment(patientA, doctor, LocalDate.of(2024, 2, 1), LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        createAppointment(patientA, doctor, LocalDate.of(2024, 2, 2), LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        createAppointment(patientB, doctor, LocalDate.of(2024, 2, 3), LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        createAppointment(patientA, doctor, LocalDate.of(2024, 2, 5), LocalTime.of(10, 0), AppointmentStatus.CANCELLED);
        createAppointment(patientB, doctor, LocalDate.of(2024, 2, 6), LocalTime.of(10, 0), AppointmentStatus.NO_SHOW);
        createAppointment(patientA, doctor, LocalDate.of(2024, 2, 7), LocalTime.of(11, 0), AppointmentStatus.SCHEDULED);
        createAppointment(patientB, doctor, LocalDate.of(2024, 2, 8), LocalTime.of(11, 0), AppointmentStatus.SCHEDULED);
        Appointment attended = createAppointment(patientA, doctor, LocalDate.of(2024, 2, 9), LocalTime.of(12, 0), AppointmentStatus.ARRIVED);
        createAppointment(patientB, otherDoctor, LocalDate.of(2024, 3, 1), LocalTime.of(9, 0), AppointmentStatus.COMPLETED);
        createAppointment(patientA, doctor, LocalDate.of(2023, 6, 15), LocalTime.of(9, 0), AppointmentStatus.COMPLETED);

        // Pharmacy side: in-house dispensing (DDP-30) - DISPENSED prescription
        createDispensedPrescription(attended, patientA, doctor,
                item(paracetamol, 3), item(amoxicillin, 2));

        // Pharmacy side: external dispensing (DDP-25) - one handed over, one cancelled
        createExternalEvent(patientA, LocalDateTime.of(2024, 3, 10, 10, 30),
                Status.DISPENSED, line(paracetamol, 5));
        createExternalEvent(patientA, LocalDateTime.of(2024, 3, 11, 9, 0),
                Status.CANCELLED, line(vitamin, 10));
    }

    // ------------------------------------------------------------------
    // AC1 - clinic happy path (functional part; performance in the perf test)
    // ------------------------------------------------------------------

    @Test
    void clinicReport_returnsAggregatesWithoutClinicalFields() throws Exception {
        JsonNode data = data(mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn());

        assertThat(data.get("totalAppointments").asLong()).isEqualTo(9);      // out-of-range excluded
        assertThat(data.get("consultationCount").asLong()).isEqualTo(4);      // completed
        assertThat(data.get("scheduled").asLong()).isEqualTo(2);              // queue statistics
        assertThat(data.get("arrived").asLong()).isEqualTo(1);
        assertThat(data.get("cancelled").asLong()).isEqualTo(1);
        assertThat(data.get("noShow").asLong()).isEqualTo(1);
        assertThat(data.get("uniquePatients").asLong()).isEqualTo(2);

        // BR006: aggregates only, no clinical fields
        String body = data(mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andReturn()).toString();
        assertThat(body).doesNotContain("clinicalNotes").doesNotContain("reason");
    }

    @Test
    void clinicReport_narrowsToASingleDoctor() throws Exception {
        JsonNode data = data(mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("doctorId", String.valueOf(doctor.getUserId()))
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(data.get("totalAppointments").asLong()).isEqualTo(8);
        assertThat(data.get("consultationCount").asLong()).isEqualTo(3);
        assertThat(data.get("doctorId").asLong()).isEqualTo(doctor.getUserId());
    }

    // ------------------------------------------------------------------
    // AC2 - unknown pharmacy report type
    // ------------------------------------------------------------------

    @Test
    void pharmacyReport_unknownTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "foo")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Unknown report type"))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    @Test
    void pharmacyReport_missingTypeReturns400() throws Exception {
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown report type"));
    }

    // ------------------------------------------------------------------
    // AC3 - oversized or inverted date ranges
    // ------------------------------------------------------------------

    @Test
    void clinicReport_rangeOverOneYearReturns422() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", "2023-12-01").param("to", "2024-12-02") // 366 days
                        .with(as(admin)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Date range too large, narrow your range"))
                .andExpect(content().string(containsString("\"data\":null")));
    }

    @Test
    void pharmacyReport_rangeOverOneYearReturns422_evenWhenExporting() throws Exception {
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "expiry")
                        .param("from", "2023-01-01").param("to", "2024-01-02")
                        .param("format", "csv")
                        .with(as(pharmacist)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value("Date range too large, narrow your range"));
    }

    @Test
    void reports_fromAfterToReturns400() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", TO.toString()).param("to", FROM.toString())
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    // ------------------------------------------------------------------
    // Pharmacy report types
    // ------------------------------------------------------------------

    @Test
    void stockUsageReport_sumsInHouseAndExternalDispensing() throws Exception {
        JsonNode data = data(mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "stock-usage")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("STOCK_USAGE"))
                .andReturn());

        List<String> names = new ArrayList<>();
        data.get("stockUsage").forEach(row -> names.add(row.get("medicineName").asText()));
        assertThat(names).containsExactly("Paracetamol 500mg", "Amoxicillin 500mg"); // 8 then 2, desc order

        assertThat(data.get("stockUsage").get(0).get("unitsDispensed").asLong())
                .isEqualTo(8); // 5 external (DDP-25) + 3 in-house (DDP-30), counted once each
        assertThat(data.get("stockUsage").get(1).get("unitsDispensed").asLong()).isEqualTo(2);
        // the CANCELLED external event never counts
        assertThat(names).doesNotContain("Vitamin C 1000mg");
    }

    @Test
    void expiryReport_reflectsQuarantineAndExpiryDataOfDdp21() throws Exception {
        JsonNode data = data(mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "expiry")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("EXPIRY"))
                .andReturn());

        JsonNode rows = data.get("expiry");
        assertThat(rows.size()).isEqualTo(2); // Vitamin C expires outside the range
        assertThat(rows.get(0).get("medicineName").asText()).isEqualTo("Paracetamol 500mg");
        assertThat(rows.get(0).get("expiryDate").asText()).isEqualTo("2024-06-15");
        assertThat(rows.get(0).get("quarantined").asBoolean()).isFalse();
        assertThat(rows.get(1).get("medicineName").asText()).isEqualTo("Amoxicillin 500mg");
        assertThat(rows.get(1).get("expiryDate").asText()).isEqualTo("2024-08-20");
        assertThat(rows.get(1).get("quarantined").asBoolean()).isTrue(); // AC4: same flag DDP-21 maintains
    }

    @Test
    void dispensingHistory_includesBothSourcesAndMasksContacts() throws Exception {
        JsonNode data = data(mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "dispensing-history")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("DISPENSING_HISTORY"))
                .andExpect(jsonPath("$.data.dispensingHistory.length()").value(3))
                .andReturn());

        List<String> sources = new ArrayList<>();
        data.get("dispensingHistory").forEach(row -> sources.add(row.get("source").asText()));
        assertThat(sources).contains("EXTERNAL", "IN-HOUSE"); // DDP-25 and DDP-30

        // AC6: contact partially masked, never the raw number
        data.get("dispensingHistory").forEach(row ->
                assertThat(row.get("patientContactNo").asText()).isEqualTo(PATIENT_A_MASKED));
    }

    // ------------------------------------------------------------------
    // AC5 - CSV/PDF export equals the on-screen data (same query)
    // ------------------------------------------------------------------

    @Test
    void csvExport_matchesTheOnScreenDispensingHistory() throws Exception {
        JsonNode onScreen = data(mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "dispensing-history")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isOk())
                .andReturn());

        MvcResult export = mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "dispensing-history")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("format", "csv")
                        .with(as(pharmacist)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".csv")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn();
        String csv = export.getResponse().getContentAsString();

        // header + exactly as many rows as the screen shows
        assertThat(csv.strip().split("\r\n")).hasSize(onScreen.get("dispensingHistory").size() + 1);
        assertThat(csv).contains("Patient Contact (Masked)");

        // every on-screen value appears in the export; masked, not raw
        for (JsonNode row : onScreen.get("dispensingHistory")) {
            assertThat(csv).contains(row.get("medicineName").asText());
            assertThat(csv).contains(row.get("patientContactNo").asText());
            assertThat(csv).contains(String.valueOf(row.get("quantity").asInt()));
            assertThat(csv).contains(row.get("source").asText());
        }
        assertThat(csv).doesNotContain(PATIENT_A_CONTACT);
    }

    @Test
    void csvExport_matchesTheOnScreenClinicTotals() throws Exception {
        JsonNode onScreen = data(mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andReturn());

        String csv = mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("format", "csv")
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsString();

        assertThat(csv).contains("Total Appointments")
                .contains(String.valueOf(onScreen.get("totalAppointments").asLong()))
                .contains(String.valueOf(onScreen.get("uniquePatients").asLong()));
    }

    @Test
    void pdfExport_returnsPdfFileBytes() throws Exception {
        byte[] pdf = mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "expiry")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("format", "pdf")
                        .with(as(pharmacist)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString(".pdf")))
                .andExpect(content().contentTypeCompatibleWith("application/pdf"))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
    }

    @Test
    void export_unknownFormatReturns400() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("format", "xls")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void exports_areWrittenToTheAuditLog() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .param("format", "csv")
                        .with(as(admin)))
                .andExpect(status().isOk());

        List<AuditLog> exports = auditLogRepository.findAll().stream()
                .filter(entry -> "REPORT_EXPORT".equals(entry.getActionType())
                        && "REPORT".equals(entry.getEntityType()))
                .toList();
        assertThat(exports).isNotEmpty();
    }

    // ------------------------------------------------------------------
    // AC7 - RBAC (SDS Table 9)
    // ------------------------------------------------------------------

    @Test
    void rbac_pharmacistCannotGenerateClinicReport() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rbac_adminCannotGeneratePharmacyReport() throws Exception {
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "stock-usage")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(admin)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rbac_doctorIsDeniedBothReportGroups() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(doctor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "expiry")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(doctor)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rbac_allowedRolesPass() throws Exception {
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(admin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/clinic")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(superAdmin)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "stock-usage")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(pharmacist)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/reports/pharmacy")
                        .param("type", "stock-usage")
                        .param("from", FROM.toString()).param("to", TO.toString())
                        .with(as(superAdmin)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private RequestPostProcessor as(User user) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
        RolePermission.getPermissions(user.getRole()).stream()
                .map(SimpleGrantedAuthority::new)
                .forEach(authorities::add);
        return user(user.getUsername()).authorities(authorities);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private User createUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("rep-" + role.name().toLowerCase() + "-" + unique);
        user.setEmail("rep-" + unique + "@onecare.test");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private Patient createPatient(String name, String contactNo) {
        Patient patient = new Patient();
        patient.setFullName(name);
        patient.setDateOfBirth(LocalDate.of(1990, 5, 12));
        patient.setContactNo(contactNo);
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private Medicine createMedicine(String name, LocalDate expiryDate, boolean quarantined) {
        Medicine medicine = new Medicine();
        medicine.setName(name);
        medicine.setPrice(new BigDecimal("1.00"));
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

    private Appointment createAppointment(Patient patient, User doctor, LocalDate date,
                                          LocalTime slot, AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(date);
        appointment.setTimeSlot(slot);
        appointment.setStatus(status);
        appointment.setReason("Checkup");
        return appointmentRepository.save(appointment);
    }

    private PrescriptionItem item(Medicine medicine, int quantity) {
        PrescriptionItem item = new PrescriptionItem();
        item.setItemType(PrescriptionItemType.IN_HOUSE);
        item.setMedicine(medicine);
        item.setQuantity(quantity);
        item.setDosage("1 tablet");
        item.setFrequency("twice daily");
        item.setDurationDays(5);
        return item;
    }

    /** DDP-30 in-house dispensing: a prescription marked DISPENSED. */
    private Prescription createDispensedPrescription(Appointment appointment, Patient patient,
                                                     User doctor, PrescriptionItem... items) {
        Prescription prescription = new Prescription();
        prescription.setAppointment(appointment);
        prescription.setPatient(patient);
        prescription.setDoctor(doctor);
        prescription.setDate(appointment.getAppointmentDate());
        prescription.setStatus(PrescriptionStatus.DISPENSED);
        for (PrescriptionItem item : items) {
            item.setPrescription(prescription);
            prescription.getItems().add(item);
        }
        prescription = prescriptionRepository.save(prescription);
        prescriptionItemRepository.saveAll(prescription.getItems());
        return prescription;
    }

    private DispensingItem line(Medicine medicine, int quantity) {
        DispensingItem line = new DispensingItem();
        line.setMedicine(medicine);
        line.setQuantityDispensed(quantity);
        return line;
    }

    /** DDP-25 external dispensing event. */
    private ExternalDispensing createExternalEvent(Patient patient, LocalDateTime dispenseDate,
                                                   Status status, DispensingItem... lines) {
        ExternalDispensing event = new ExternalDispensing();
        for (DispensingItem line : lines) {
            event.addItem(line);
        }
        event.setPatientId(patient.getPatientId());
        event.setStatus(status);
        event.setVerificationMethod("COMMON");
        event.setDispenseDate(dispenseDate);
        event.setDispensedAt(dispenseDate);
        event.setDeliveryMethod(DeliveryMethod.PICK_UP);
        return externalDispensingRepository.save(event);
    }
}
