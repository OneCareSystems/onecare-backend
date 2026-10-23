package com.onecare.backend.integration;

import com.onecare.backend.entity.*;
import com.onecare.backend.enums.*;
import com.onecare.backend.repository.*;
import com.onecare.backend.security.RolePermission;
import com.onecare.backend.service.MedicineService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.application.name=dispensing-integration")
@AutoConfigureMockMvc
class PrescriptionDispensingIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired AppointmentRepository appointments;
    @Autowired MedicineRepository medicines;
    @MockitoSpyBean PrescriptionRepository prescriptions;
    @MockitoSpyBean MedicineService stockService;
    Long id, medicineId, itemId;
    String doctorName, pharmacistName;

    @BeforeEach void setup() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            String unique = UUID.randomUUID().toString();
            User doctor = staff("doctor" + unique, Role.DOCTOR); doctorName = doctor.getUsername();
            pharmacistName = staff("pharm" + unique, Role.PHARMACIST).getUsername();
            Patient patient = new Patient(); patient.setFullName("Dispensing Patient");
            patient.setDateOfBirth(LocalDate.of(1990, 1, 1)); patient.setContactNo("0771234567");
            patient.setGender(Gender.OTHER);
            patient.setIsActive(true); patients.save(patient);
            Appointment appointment = new Appointment(); appointment.setDoctor(doctor); appointment.setPatient(patient);
            appointment.setAppointmentDate(LocalDate.now()); appointment.setTimeSlot(LocalTime.NOON);
            appointment.setStatus(AppointmentStatus.SCHEDULED); appointment.setClinicalNotes("PRIVATE CLINICAL NOTE");
            appointments.save(appointment);
            Medicine medicine = new Medicine(); medicine.setName(unique); medicine.setCategory("Test");
            medicine.setUnit("Tablet"); medicine.setPrice(BigDecimal.TEN); medicine.setStockQuantity(10);
            medicine.setReorderLevel(1); medicine.setExpiryDate(LocalDate.now().plusDays(1));
            medicine.setCreatedAt(LocalDateTime.now()); medicine.setUpdatedAt(LocalDateTime.now());
            medicines.save(medicine); medicineId = medicine.getMedicineId();
            Prescription p = new Prescription(); p.setDoctor(doctor); p.setPatient(patient); p.setAppointment(appointment);
            p.setDate(LocalDate.now()); p.setStatus(PrescriptionStatus.ISSUED);
            PrescriptionItem item = new PrescriptionItem(); item.setPrescription(p); item.setMedicine(medicine);
            item.setItemType(PrescriptionItemType.IN_HOUSE); item.setQuantity(6); item.setDosage("1");
            item.setFrequency("Daily"); item.setDurationDays(6); p.getItems().add(item);
            prescriptions.saveAndFlush(p); id = p.getPrescriptionId(); itemId = item.getItemId();
        });
    }

    User staff(String name, Role role) {
        User u = new User(); u.setUsername(name); u.setEmail(name + "@test.local"); u.setPasswordHash("hash");
        u.setRole(role); u.setIsActive(true); return users.save(u);
    }
    RequestPostProcessor as(String name, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        RolePermission.getPermissions(role).forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
        return user(new com.onecare.backend.security.AppUserDetailsService.AppUserDetails(
                users.findByUsername(name).map(User::getUserId).orElse(null), name, "hash",
                true, true, true, true, authorities));
    }
    org.springframework.test.web.servlet.ResultActions dispense(boolean partial) throws Exception {
        return mvc.perform(post("/api/prescriptions/{id}/dispense", id).with(as(pharmacistName, Role.PHARMACIST))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPartial\":" + partial + "}"));
    }
    int stock() { return jdbc.queryForObject("select stock_quantity from medicines where medicine_id=?", Integer.class, medicineId); }
    int quantity() { return jdbc.queryForObject("select quantity_dispensed from prescription_items where item_id=?", Integer.class, itemId); }
    String prescriptionStatus() { return jdbc.queryForObject("select status from prescriptions where prescription_id=?", String.class, id); }

    @Test void fullDispenseAndAudit() throws Exception {
        dispense(false).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPENSED"));
        assertEquals(4, stock()); assertEquals(6, quantity());
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_log a join users u on a.user_id=u.user_id where a.action_type='PRESCRIPTION_DISPENSE' and a.entity_id=? and u.username=? and a.timestamp is not null", Integer.class, id.toString(), pharmacistName));
    }
    @Test void shortfallThenPartialThenFollowUp() throws Exception {
        jdbc.update("update medicines set stock_quantity=2 where medicine_id=?", medicineId);
        dispense(false).andExpect(status().isConflict()).andExpect(jsonPath("$.data.items[0].shortfallQuantity").value(4));
        assertEquals(2, stock()); assertEquals(0, quantity()); assertEquals("ISSUED", prescriptionStatus());
        dispense(true).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PARTIALLY_DISPENSED"));
        assertEquals(0, stock()); assertEquals(2, quantity());
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(as(pharmacistName, Role.PHARMACIST)))
                .andExpect(jsonPath("$.data.items[0].remainingQuantity").value(4));
        jdbc.update("update medicines set stock_quantity=10, price=99 where medicine_id=?", medicineId);
        dispense(false).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISPENSED"));
        assertEquals(6, stock()); assertEquals(6, quantity());
        assertEquals(0, BigDecimal.TEN.compareTo(jdbc.queryForObject("select unit_price from prescription_items where item_id=?", BigDecimal.class, itemId)));
    }
    @ParameterizedTest @EnumSource(value = PrescriptionStatus.class, names = {"DISPENSED", "DISPENSED_EXTERNAL", "CANCELLED"})
    void terminalStatesAreBadRequest(PrescriptionStatus terminal) throws Exception {
        jdbc.update("update prescriptions set status=? where prescription_id=?", terminal.name(), id);
        dispense(false).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Prescription already dispensed"))
                .andExpect(jsonPath("$.data").isEmpty());
        assertEquals(10, stock()); assertEquals(0, quantity());
    }
    @Test void previewAndListAreNonClinicalAndReadOnly() throws Exception {
        String list = mvc.perform(get("/api/prescriptions?status=ISSUED").with(as(pharmacistName, Role.PHARMACIST)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].items").isArray()).andReturn().getResponse().getContentAsString();
        String preview = mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(as(pharmacistName, Role.PHARMACIST)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].dispensable").value(true))
                .andReturn().getResponse().getContentAsString();
        for (String response : List.of(list, preview)) {
            assertFalse(response.toLowerCase().contains("clinical")); assertFalse(response.toLowerCase().contains("diagnosis"));
        }
        assertEquals(10, stock()); assertEquals(0, quantity()); assertEquals("ISSUED", prescriptionStatus());
    }
    @Test void unsafeMedicinesBlockPreviewAndMutation() throws Exception {
        jdbc.update("update medicines set is_quarantined=true where medicine_id=?", medicineId);
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(as(pharmacistName, Role.PHARMACIST)))
                .andExpect(jsonPath("$.data.items[0].dispensable").value(false))
                .andExpect(jsonPath("$.data.items[0].reason").value(org.hamcrest.Matchers.containsString("quarantined")));
        dispense(true).andExpect(status().isBadRequest());
        jdbc.update("update medicines set is_quarantined=false, expiry_date=? where medicine_id=?", LocalDate.now().minusDays(1), medicineId);
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(as(pharmacistName, Role.PHARMACIST)))
                .andExpect(jsonPath("$.data.items[0].reason").value(org.hamcrest.Matchers.containsString("expired")));
        dispense(true).andExpect(status().isBadRequest()); assertEquals(10, stock()); assertEquals(0, quantity());
    }
    @ParameterizedTest @EnumSource(Role.class)
    void permissionsForPreviewAndDispense(Role role) throws Exception {
        String name = role == Role.DOCTOR ? doctorName : pharmacistName;
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(as(name, role))).andExpect(status().isOk());
        mvc.perform(post("/api/prescriptions/{id}/dispense", id).with(as(name, role))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPartial\":false}"))
                .andExpect(role == Role.PHARMACIST || role == Role.SUPER_ADMIN ? status().isOk() : status().isForbidden());
    }
    @Test void doctorCannotReadAnotherDoctorsPrescription() throws Exception {
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(as("other-doctor", Role.DOCTOR)))
                .andExpect(status().isForbidden());
        String list = mvc.perform(get("/api/prescriptions?status=ISSUED").with(as(doctorName, Role.DOCTOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andReturn().getResponse().getContentAsString();
        assertTrue(list.contains(id.toString()));
    }
    @Test void missingAndNoPermissions() throws Exception {
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", Long.MAX_VALUE).with(as(pharmacistName, Role.PHARMACIST)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/prescriptions/{id}/dispense", Long.MAX_VALUE).with(as(pharmacistName, Role.PHARMACIST))
                .contentType(MediaType.APPLICATION_JSON).content("{\"confirmPartial\":false}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/prescriptions/{id}/dispense-preview", id).with(user("no-permission"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/prescriptions/{id}/dispense", id).with(user("no-permission"))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
    }
    @Test void concurrentRequestsDeductOnceAndReturnConflict() throws Exception {
        CyclicBarrier snapshots = new CyclicBarrier(2);
        Long revision = prescriptions.findDispenseRevision(id).orElseThrow();
        doAnswer(invocation -> {
            snapshots.await(10, TimeUnit.SECONDS);
            return Optional.of(revision);
        }).when(prescriptions).findDispenseRevision(id);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> request = () -> dispense(false).andReturn().getResponse().getStatus();
            Future<Integer> first = pool.submit(request), second = pool.submit(request);
            List<Integer> statuses = new ArrayList<>(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)));
            Collections.sort(statuses); assertEquals(List.of(200, 409), statuses);
        }
        assertEquals(4, stock()); assertEquals(6, quantity());
    }
    @Test void repeatedMedicineLinesCannotDeductMoreThanStock() throws Exception {
        Long secondItemId = new TransactionTemplate(transactions).execute(tx -> {
            Prescription p = prescriptions.findById(id).orElseThrow();
            PrescriptionItem second = new PrescriptionItem(); second.setPrescription(p);
            second.setMedicine(medicines.findById(medicineId).orElseThrow());
            second.setItemType(PrescriptionItemType.IN_HOUSE); second.setQuantity(6);
            second.setDosage("1"); second.setFrequency("Daily"); second.setDurationDays(6);
            p.getItems().add(second); return prescriptions.saveAndFlush(p).getItems().getLast().getItemId();
        });
        dispense(false).andExpect(status().isConflict()); assertEquals(10, stock());
        dispense(true).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PARTIALLY_DISPENSED"));
        assertEquals(0, stock()); assertEquals(6, quantity());
        assertEquals(4, jdbc.queryForObject("select quantity_dispensed from prescription_items where item_id=?", Integer.class, secondItemId));
    }

    @Test void laterFailureRollsBackStockAndQuantities() throws Exception {
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("Simulated failure after stock save"); })
                .when(stockService).updateStock(eq(medicineId), any());
        dispense(false).andExpect(status().isInternalServerError());
        assertEquals(10, stock()); assertEquals(0, quantity()); assertEquals("ISSUED", prescriptionStatus());
    }
}
