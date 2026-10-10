package com.onecare.backend.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ExternalDispensingControllerIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final long DOCTOR_ID = 9101L;
    private static final long PATIENT_ID = 9102L;

    @BeforeEach
    void setup() {

        /*
         * Delete only test rows.
         * Children first because of foreign keys.
         */
        jdbcTemplate.update(
                "DELETE FROM dispensing_items WHERE dispense_id >= 9200");

        jdbcTemplate.update(
                "DELETE FROM external_dispensing WHERE dispense_id >= 9200");

        jdbcTemplate.update(
                "DELETE FROM prescription_items WHERE prescription_id >= 9300");

        jdbcTemplate.update(
                "DELETE FROM prescriptions WHERE prescription_id >= 9300");

        jdbcTemplate.update(
                "DELETE FROM appointments WHERE appointment_id >= 9400");

        jdbcTemplate.update(
                "DELETE FROM medicines WHERE medicine_id >= 9500");

        jdbcTemplate.update(
                "DELETE FROM patients WHERE patient_id = ?",
                PATIENT_ID);

        jdbcTemplate.update(
                "DELETE FROM users WHERE user_id = ?",
                DOCTOR_ID);

        insertDoctor();
        insertPatient();
    }

    /**
     * AC1:
     *
     * sufficient stock:
     * requested quantity = 3
     * stock = 10
     *
     * Expected:
     * stock becomes 7
     * event becomes DISPENSED
     * prescription becomes DISPENSED
     */
    @Test
    @WithMockUser(username = "pharmacist-test", authorities = {
                    "ROLE_PHARMACIST",
                    "PRESCRIPTION_DISPENSE"
    })

    void complete_withSufficientStock_dispensesExactQuantity()
            throws Exception {

        long appointmentId = 9401L;
        long prescriptionId = 9301L;
        long medicineId = 9501L;
        long dispenseId = 9201L;

        insertAppointment(appointmentId);

        insertPrescription(
                prescriptionId,
                appointmentId,
                "ISSUED");

        insertMedicine(
                medicineId,
                10);

        insertExternalDispensing(
                dispenseId,
                prescriptionId,
                "VERIFIED",
                "PENDING");

        insertDispensingItem(
                9601L,
                dispenseId,
                medicineId,
                3);

        mvc.perform(
                post(
                        "/api/external-dispensing/"
                                + dispenseId
                                + "/complete"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.success")
                                .value(true))
                .andExpect(
                        jsonPath("$.data.status")
                                .value("DISPENSED"));

        Integer stock = jdbcTemplate.queryForObject(
                """
                        SELECT stock_quantity
                        FROM medicines
                        WHERE medicine_id = ?
                        """,
                Integer.class,
                medicineId);

        assert stock != null;
        assert stock == 7;

        String dispensingStatus = jdbcTemplate.queryForObject(
                """
                        SELECT status
                        FROM external_dispensing
                        WHERE dispense_id = ?
                        """,
                String.class,
                dispenseId);

        assert "DISPENSED"
                .equals(dispensingStatus);

        String prescriptionStatus = jdbcTemplate.queryForObject(
                """
                        SELECT status
                        FROM prescriptions
                        WHERE prescription_id = ?
                        """,
                String.class,
                prescriptionId);

        assert "DISPENSED"
                .equals(prescriptionStatus);
    }

    /**
     * AC3:
     *
     * requested quantity = 5
     * available stock = 2
     *
     * Expected:
     * dispense only 2
     * stock becomes 0
     * event becomes PARTIALLY_DISPENSED
     * prescription remains ISSUED
     */
    @Test
    @WithMockUser(username = "pharmacist-test", authorities = "PRESCRIPTION_DISPENSE")
    void complete_withInsufficientStock_partiallyDispenses()
            throws Exception {

        long appointmentId = 9402L;
        long prescriptionId = 9302L;
        long medicineId = 9502L;
        long dispenseId = 9202L;

        insertAppointment(appointmentId);

        insertPrescription(
                prescriptionId,
                appointmentId,
                "ISSUED");

        insertMedicine(
                medicineId,
                2);

        insertExternalDispensing(
                dispenseId,
                prescriptionId,
                "VERIFIED",
                "PENDING");

        insertDispensingItem(
                9602L,
                dispenseId,
                medicineId,
                5);

        mvc.perform(
                post(
                        "/api/external-dispensing/"
                                + dispenseId
                                + "/complete"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.success")
                                .value(true))
                .andExpect(
                        jsonPath("$.data.status")
                                .value("PARTIALLY_DISPENSED"));

        Integer stock = jdbcTemplate.queryForObject(
                """
                        SELECT stock_quantity
                        FROM medicines
                        WHERE medicine_id = ?
                        """,
                Integer.class,
                medicineId);

        assert stock != null;
        assert stock == 0;

        Integer quantityDispensed = jdbcTemplate.queryForObject(
                """
                        SELECT quantity_dispensed
                        FROM dispensing_items
                        WHERE dispensing_item_id = ?
                        """,
                Integer.class,
                9602L);

        assert quantityDispensed != null;
        assert quantityDispensed == 2;

        String dispensingStatus = jdbcTemplate.queryForObject(
                """
                        SELECT status
                        FROM external_dispensing
                        WHERE dispense_id = ?
                        """,
                String.class,
                dispenseId);

        assert "PARTIALLY_DISPENSED"
                .equals(dispensingStatus);

        String prescriptionStatus = jdbcTemplate.queryForObject(
                """
                        SELECT status
                        FROM prescriptions
                        WHERE prescription_id = ?
                        """,
                String.class,
                prescriptionId);

        assert "ISSUED"
                .equals(prescriptionStatus);
    }

    /**
     * AC2:
     *
     * DISPENSED prescription cannot be
     * marked for external dispensing.
     *
     * GlobalExceptionHandler converts
     * BusinessRuleException to HTTP 400.
     */
    @Test
    @WithMockUser(username = "pharmacist-test", authorities = {
                    "ROLE_PHARMACIST",
                    "PRESCRIPTION_DISPENSE"
    })
    void markExternal_whenPrescriptionIsDispensed_returns400()
            throws Exception {

        long appointmentId = 9403L;
        long prescriptionId = 9303L;

        insertAppointment(
                appointmentId);

        insertPrescription(
                prescriptionId,
                appointmentId,
                "DISPENSED");

        mvc.perform(
                patch(
                        "/api/prescriptions/"
                                + prescriptionId
                                + "/mark-external"))
                .andExpect(
                        status().isBadRequest())
                .andExpect(
                        jsonPath("$.success")
                                .value(false));

        Integer count = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*)
                        FROM external_dispensing
                        WHERE prescription_id = ?
                        """,
                Integer.class,
                prescriptionId);

        assert count != null;
        assert count == 0;
    }

    /*
     * ---------------------------------------------------------
     * Test data helpers
     * ---------------------------------------------------------
     */

    private void insertDoctor() {

        jdbcTemplate.update(
                """
                        INSERT INTO users (
                            user_id,
                            username,
                            email,
                            password_hash,
                            password_change_required,
                            role,
                            is_active,
                            failed_attempts,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                DOCTOR_ID,
                "ddp24-doctor",
                "ddp24-doctor@example.com",
                "test-password-hash",
                false,
                "DOCTOR",
                true,
                0,
                Timestamp.valueOf(
                        LocalDateTime.now()),
                Timestamp.valueOf(
                        LocalDateTime.now()));
    }

    private void insertPatient() {

        jdbcTemplate.update(
                """
                        INSERT INTO patients (
                            patient_id,
                            full_name,
                            date_of_birth,
                            contact_no,
                            is_active,
                            gender,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                PATIENT_ID,
                "DDP24 Test Patient",
                Date.valueOf(
                        LocalDate.of(
                                1995,
                                1,
                                1)),
                "+94770000000",
                true,
                "MALE",
                Timestamp.valueOf(
                        LocalDateTime.now()),
                Timestamp.valueOf(
                        LocalDateTime.now()));
    }

    private void insertAppointment(
            long appointmentId) {

        jdbcTemplate.update(
                """
                        INSERT INTO appointments (
                            appointment_id,
                            patient_id,
                            doctor_id,
                            appointment_date,
                            time_slot,
                            reason,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                appointmentId,
                PATIENT_ID,
                DOCTOR_ID,
                Date.valueOf(
                        LocalDate.now()),
                Time.valueOf(
                        LocalTime.of(
                                10,
                                0)),
                "DDP24 integration test",
                "COMPLETED",
                Timestamp.valueOf(
                        LocalDateTime.now()),
                Timestamp.valueOf(
                        LocalDateTime.now()));
    }

    private void insertPrescription(
            long prescriptionId,
            long appointmentId,
            String status) {

        jdbcTemplate.update(
                """
                        INSERT INTO prescriptions (
                            prescription_id,
                            appointment_id,
                            doctor_id,
                            patient_id,
                            date,
                            status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                prescriptionId,
                appointmentId,
                DOCTOR_ID,
                PATIENT_ID,
                Date.valueOf(
                        LocalDate.now()),
                status,
                Timestamp.valueOf(
                        LocalDateTime.now()),
                Timestamp.valueOf(
                        LocalDateTime.now()));
    }

    private void insertMedicine(
            long medicineId,
            int stock) {

        jdbcTemplate.update(
                """
                        INSERT INTO medicines (
                            medicine_id,
                            name,
                            price,
                            category,
                            unit,
                            reorder_level,
                            is_quarantined,
                            stock_quantity,
                            expiry_date,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                medicineId,
                "Paracetamol",
                2.50,
                "Pain Relief",
                "tablet",
                5,
                false,
                stock,
                Date.valueOf(
                        LocalDate.now()
                                .plusMonths(6)),
                Timestamp.valueOf(
                        LocalDateTime.now()),
                Timestamp.valueOf(
                        LocalDateTime.now()));
    }

    private void insertExternalDispensing(
            long dispenseId,
            long prescriptionId,
            String verificationStatus,
            String status) {

        jdbcTemplate.update(
                """
                        INSERT INTO external_dispensing (
                            dispense_id,
                            prescription_id,
                            patient_id,
                            verification_method,
                            verification_status,
                            verified_at,
                            retry_count,
                            dispense_date,
                            status,
                            delivery_method,
                            dispensed_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                dispenseId,
                prescriptionId,
                PATIENT_ID,
                "PHONE",
                verificationStatus,
                Timestamp.valueOf(
                        LocalDateTime.now()),
                1,
                Timestamp.valueOf(
                        LocalDateTime.now()),
                status,
                "PICK_UP",
                Timestamp.valueOf(
                        LocalDateTime.now()));
    }

    private void insertDispensingItem(
            long itemId,
            long dispenseId,
            long medicineId,
            int quantity) {

        jdbcTemplate.update(
                """
                        INSERT INTO dispensing_items (
                            dispensing_item_id,
                            dispense_id,
                            prescription_item_id,
                            medicine_id,
                            quantity_dispensed
                        )
                        VALUES (?, ?, NULL, ?, ?)
                        """,
                itemId,
                dispenseId,
                medicineId,
                quantity);
    }
}