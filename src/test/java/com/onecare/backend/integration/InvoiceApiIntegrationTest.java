package com.onecare.backend.integration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.onecare.backend.entity.Appointment;
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
import com.onecare.backend.repository.ExternalDispensingRepository;
import com.onecare.backend.repository.InvoiceRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DDP-23 end-to-end coverage: controller -> service -> repository on the test
 * database (AC1, AC2, AC3, AC4, AC5, AC6, AC7, AC8 plus list filters).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional // every test rolls back, so the database stays clean
class InvoiceApiIntegrationTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Keeps money scale intact: "0.00" instead of Jackson's default "0".
     * (USE_BIG_DECIMAL_FOR_FLOATS alone still strips trailing zeros.)
     */
    private static final ObjectMapper MONEY_JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .nodeFactory(new JsonNodeFactory(true))
            .build();

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
    private InvoiceRepository invoiceRepository;

    private User admin;
    private User superAdmin;
    private User doctor;
    private User pharmacist;
    private Patient patient;
    private Medicine cheapMedicine;
    private Medicine dearMedicine;
    private Appointment appointment;
    private Prescription prescription;

    @BeforeEach
    void seed() {
        admin = createUser(Role.ADMIN);
        superAdmin = createUser(Role.SUPER_ADMIN);
        doctor = createUser(Role.DOCTOR);
        pharmacist = createUser(Role.PHARMACIST);

        patient = createPatient();
        cheapMedicine = createMedicine("Paracetamol 500mg", "0.10");
        dearMedicine = createMedicine("Ibuprofen 400mg", "19.99");

        appointment = createAppointment(patient, doctor, AppointmentStatus.COMPLETED, LocalTime.of(9, 0));
        prescription = createDispensedPrescription(appointment, patient, doctor,
                item(cheapMedicine, 3), item(dearMedicine, 3));
    }

    // ------------------------------------------------------------------
    // AC1 - happy path
    // ------------------------------------------------------------------

    @Test
    void generateInvoice_containsConsultationAndItemLinesWithCentPrecision() throws Exception {
        JsonNode invoice = generateInvoice(admin);

        String year = String.valueOf(LocalDate.now().getYear());
        assertThat(invoice.get("invoiceNumber").asText()).matches("INV-" + year + "-\\d{6}");
        assertThat(invoice.get("status").asText()).isEqualTo("UNPAID");
        assertThat(invoice.get("amountPaid").asText()).isEqualTo("0.00");
        assertThat(invoice.get("patientId").asLong()).isEqualTo(patient.getPatientId());
        assertThat(invoice.get("appointmentId").asLong()).isEqualTo(appointment.getAppointmentId());
        assertThat(invoice.get("createdBy").asLong()).isEqualTo(admin.getUserId());
        assertThat(invoice.get("createdAt").asText()).isNotBlank();
        assertThat(invoice.get("payments").size()).isZero();

        // consultation charge + 3 x 0.10 + 3 x 19.99
        assertThat(invoice.get("items").size()).isEqualTo(3);

        JsonNode consultation = invoice.get("items").get(0);
        assertThat(consultation.get("medicineId").isNull()).isTrue();
        assertThat(consultation.get("description").asText()).isEqualTo("Consultation charge");
        assertThat(consultation.get("quantity").asInt()).isEqualTo(1);
        assertThat(consultation.get("unitPrice").asText()).isEqualTo("500.00");

        assertThat(invoice.get("items").get(1).get("unitPrice").asText()).isEqualTo("0.10");
        assertThat(invoice.get("items").get(1).get("lineTotal").asText()).isEqualTo("0.30");
        assertThat(invoice.get("items").get(2).get("unitPrice").asText()).isEqualTo("19.99");
        assertThat(invoice.get("items").get(2).get("lineTotal").asText()).isEqualTo("59.97");

        assertThat(invoice.get("total").asText()).isEqualTo("560.27");
        assertThat(invoice.get("outstandingBalance").asText()).isEqualTo("560.27");

        // exactly one invoice exists for this consultation
        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void generateInvoice_billsOnlyWhatWasDispensedForPartialDispensing() throws Exception {
        Medicine medicine = createMedicine("Amoxicillin 250mg", "10.00");
        Appointment other = createAppointment(patient, doctor, AppointmentStatus.COMPLETED, LocalTime.of(11, 0));
        Prescription partial = createDispensedPrescription(other, patient, doctor,
                item(medicine, 10));

        // only 3 of the 10 prescribed units were handed out
        ExternalDispensing event = new ExternalDispensing();
        event.setPrescription(partial);
        DispensingItem handover = new DispensingItem();
        handover.setPrescriptionItem(partial.getItems().get(0));
        handover.setMedicine(medicine);
        handover.setQuantityDispensed(3);
        event.addItem(handover);
        event.setStatus(Status.DISPENSED);
        event.setVerificationMethod("ID");
        event.setDispenseDate(LocalDateTime.now());
        event.setDispensedAt(LocalDateTime.now());
        event.setDeliveryMethod(DeliveryMethod.PICK_UP);
        externalDispensingRepository.save(event);

        JsonNode invoice = generateInvoice(admin, other.getAppointmentId());

        assertThat(invoice.get("items").size()).isEqualTo(2);
        assertThat(invoice.get("items").get(1).get("quantity").asInt()).isEqualTo(3);
        assertThat(invoice.get("items").get(1).get("lineTotal").asText()).isEqualTo("30.00");
        assertThat(invoice.get("total").asText()).isEqualTo("530.00");
    }

    // ------------------------------------------------------------------
    // AC7 - no double billing
    // ------------------------------------------------------------------

    @Test
    void generateInvoice_twiceForSameConsultationReturns409AndCreatesNothing() throws Exception {
        generateInvoice(admin);

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\": " + appointment.getAppointmentId() + "}")
                        .with(as(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void generateInvoice_forNonCompletedConsultationReturns400() throws Exception {
        Appointment scheduled = createAppointment(patient, doctor, AppointmentStatus.SCHEDULED, LocalTime.of(15, 0));

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\": " + scheduled.getAppointmentId() + "}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(invoiceRepository.count()).isZero();
    }

    // ------------------------------------------------------------------
    // AC2 - cash only
    // ------------------------------------------------------------------

    @Test
    void nonCashPaymentReturns400AndChangesNoState() throws Exception {
        JsonNode invoice = generateInvoice(admin);

        mockMvc.perform(post("/api/invoices/" + invoice.get("invoiceId").asLong() + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100.00, \"paymentMethod\": \"CARD\"}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        JsonNode unchanged = getInvoice(invoice.get("invoiceId").asLong());
        assertThat(unchanged.get("amountPaid").asText()).isEqualTo("0.00");
        assertThat(unchanged.get("status").asText()).isEqualTo("UNPAID");
        assertThat(unchanged.get("payments").size()).isZero();
    }

    // ------------------------------------------------------------------
    // AC4 / AC5 - partial and full payment
    // ------------------------------------------------------------------

    @Test
    void partialPayment_raisesBalanceAndKeepsInvoiceUnpaid() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();

        JsonNode paid = recordPayment(id, "200.00");

        assertThat(paid.get("amountPaid").asText()).isEqualTo("200.00");
        assertThat(paid.get("outstandingBalance").asText()).isEqualTo("360.27");
        assertThat(paid.get("status").asText()).isEqualTo("UNPAID");
        assertThat(paid.get("payments").size()).isEqualTo(1);
        assertThat(paid.get("payments").get(0).get("amount").asText()).isEqualTo("200.00");
        assertThat(paid.get("payments").get(0).get("paymentMethod").asText()).isEqualTo("CASH");
        assertThat(paid.get("payments").get(0).get("recordedBy").asLong())
                .isEqualTo(admin.getUserId());
    }

    @Test
    void fullPayment_setsAmountPaidToTotalAndStatusToPaid() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();

        recordPayment(id, "200.00");
        JsonNode settled = recordPayment(id, "360.27");

        assertThat(settled.get("amountPaid").asText()).isEqualTo("560.27");
        assertThat(settled.get("amountPaid").asText()).isEqualTo(settled.get("total").asText());
        assertThat(settled.get("outstandingBalance").asText()).isEqualTo("0.00");
        assertThat(settled.get("status").asText()).isEqualTo("PAID");
        assertThat(settled.get("payments").size()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // AC6 - invalid payment amounts
    // ------------------------------------------------------------------

    @Test
    void invalidPaymentAmountsReturn400AndChangeNothing() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();

        List<String> invalidBodies = List.of(
                "{\"amount\": 560.28, \"paymentMethod\": \"CASH\"}", // overpayment
                "{\"amount\": 0, \"paymentMethod\": \"CASH\"}",      // zero
                "{\"amount\": -10.00, \"paymentMethod\": \"CASH\"}", // negative
                "{\"amount\": 10.999, \"paymentMethod\": \"CASH\"}"); // > 2 decimals

        for (String body : invalidBodies) {
            mockMvc.perform(post("/api/invoices/" + id + "/payments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .with(as(admin)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }

        JsonNode unchanged = getInvoice(id);
        assertThat(unchanged.get("amountPaid").asText()).isEqualTo("0.00");
        assertThat(unchanged.get("status").asText()).isEqualTo("UNPAID");
        assertThat(unchanged.get("payments").size()).isZero();
    }

    @Test
    void paymentOnPaidInvoiceReturns400AndChangesNothing() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();
        recordPayment(id, "560.27");

        mockMvc.perform(post("/api/invoices/" + id + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1.00, \"paymentMethod\": \"CASH\"}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest());

        JsonNode unchanged = getInvoice(id);
        assertThat(unchanged.get("status").asText()).isEqualTo("PAID");
        assertThat(unchanged.get("amountPaid").asText()).isEqualTo("560.27");
        assertThat(unchanged.get("payments").size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // AC3 - line correction
    // ------------------------------------------------------------------

    @Test
    void lineEdit_recalculatesTotalAndKeepsConsultationLine() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();
        long cheapLineId = invoice.get("items").get(1).get("invoiceItemId").asLong();

        // keep only the cheap line with quantity 1: 500.00 + 1 x 0.10
        String body = "{\"items\": [{\"invoiceItemId\": " + cheapLineId + ", \"quantity\": 1}]}";

        MvcResult result = mockMvc.perform(put("/api/invoices/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].description").value("Consultation charge"))
                .andExpect(jsonPath("$.data.status").value("UNPAID"))
                .andReturn();

        // jsonPath cannot compare BigDecimal scale, so parse with MONEY_JSON
        assertThat(data(result).get("total").decimalValue())
                .isEqualTo(new BigDecimal("500.10"));
        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void lineEdit_onPaidInvoiceReturns400AndInvoiceRemainsUnchanged() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();
        long cheapLineId = invoice.get("items").get(1).get("invoiceItemId").asLong();
        recordPayment(id, "560.27");

        String body = "{\"items\": [{\"invoiceItemId\": " + cheapLineId + ", \"quantity\": 1}]}";

        mockMvc.perform(put("/api/invoices/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        JsonNode unchanged = getInvoice(id);
        assertThat(unchanged.get("status").asText()).isEqualTo("PAID");
        assertThat(unchanged.get("total").asText()).isEqualTo("560.27");
        assertThat(unchanged.get("items").size()).isEqualTo(3);
        assertThat(unchanged.get("items").get(1).get("quantity").asInt()).isEqualTo(3);
    }

    @Test
    void lineEdit_rejectsTotalBelowAmountPaid() throws Exception {
        JsonNode invoice = generateInvoice(admin);
        long id = invoice.get("invoiceId").asLong();
        long cheapLineId = invoice.get("items").get(1).get("invoiceItemId").asLong();
        recordPayment(id, "559.00"); // almost fully paid

        String body = "{\"items\": [{\"invoiceItemId\": " + cheapLineId + ", \"quantity\": 1}]}";

        mockMvc.perform(put("/api/invoices/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(as(admin)))
                .andExpect(status().isBadRequest());

        JsonNode unchanged = getInvoice(id);
        assertThat(unchanged.get("total").asText()).isEqualTo("560.27");
        assertThat(unchanged.get("amountPaid").asText()).isEqualTo("559.00");
    }

    // ------------------------------------------------------------------
    // List, filters & pagination
    // ------------------------------------------------------------------

    @Test
    void list_filtersByPatientInvoiceNumberStatusAndDateRange() throws Exception {
        JsonNode first = generateInvoice(admin);
        JsonNode second = generateInvoice(admin,
                createAppointment(patient, doctor, AppointmentStatus.COMPLETED, LocalTime.of(10, 0)).getAppointmentId());
        // this consultation has no prescription: a consultation-only invoice
        recordPayment(second.get("invoiceId").asLong(),
                second.get("outstandingBalance").asText()); // second is PAID

        // by status
        assertThat(listInvoices("?status=PAID").get("totalElements").asInt()).isEqualTo(1);
        assertThat(listInvoices("?status=UNPAID").get("totalElements").asInt()).isEqualTo(1);

        // by patient
        assertThat(listInvoices("?patientId=" + patient.getPatientId())
                .get("totalElements").asInt()).isEqualTo(2);
        assertThat(listInvoices("?patientId=999999").get("totalElements").asInt()).isZero();

        // by (partial) invoice number
        String number = first.get("invoiceNumber").asText();
        JsonNode byNumber = listInvoices("?invoiceNumber=" + number.substring(4));
        assertThat(byNumber.get("totalElements").asInt()).isEqualTo(1);
        assertThat(byNumber.get("content").get(0).get("invoiceNumber").asText()).isEqualTo(number);

        // by date range (today)
        String today = LocalDate.now().format(DAY);
        assertThat(listInvoices("?dateFrom=" + today + "&dateTo=" + today)
                .get("totalElements").asInt()).isEqualTo(2);

        // empty range: tomorrow
        String tomorrow = LocalDate.now().plusDays(1).format(DAY);
        assertThat(listInvoices("?dateFrom=" + tomorrow + "&dateTo=" + tomorrow)
                .get("totalElements").asInt()).isZero();

        // invalid status -> 400
        mockMvc.perform(get("/api/invoices?status=WHATEVER").with(as(admin)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void list_isPaginatedNewestFirstAndCarriesNoLineItems() throws Exception {
        generateInvoice(admin);
        generateInvoice(admin,
                createAppointment(patient, doctor, AppointmentStatus.COMPLETED, LocalTime.of(10, 0)).getAppointmentId());
        generateInvoice(admin,
                createAppointment(patient, doctor, AppointmentStatus.COMPLETED, LocalTime.of(12, 0)).getAppointmentId());

        JsonNode page = listInvoices("?page=0&size=2");
        assertThat(page.get("size").asInt()).isEqualTo(2);
        assertThat(page.get("content").size()).isEqualTo(2);
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).has("items")).isFalse();

        // newest first (createdAt never increases down the page)
        String previous = page.get("content").get(0).get("createdAt").asText();
        for (int i = 1; i < page.get("content").size(); i++) {
            String current = page.get("content").get(i).get("createdAt").asText();
            assertThat(previous.compareTo(current)).isGreaterThanOrEqualTo(0);
            previous = current;
        }

        // default page size is 20 when size is omitted
        assertThat(listInvoices("").get("size").asInt()).isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // AC8 - access control
    // ------------------------------------------------------------------

    @Test
    void doctorIsForbiddenOnEveryInvoiceEndpoint() throws Exception {
        String body = "{\"appointmentId\": " + appointment.getAppointmentId() + "}";

        mockMvc.perform(get("/api/invoices").with(as(doctor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/invoices/1").with(as(doctor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON).content(body).with(as(doctor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/invoices/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": []}").with(as(doctor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/invoices/1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1.00, \"paymentMethod\": \"CASH\"}")
                        .with(as(doctor)))
                .andExpect(status().isForbidden());

        assertThat(invoiceRepository.count()).isZero();
    }

    // The pharmacist runs counter billing: path rule + INVOICE_* permissions
    // grant every invoice endpoint (never 403), while unknown ids still 404.
    @Test
    void pharmacistHasFullInvoiceAccess() throws Exception {
        mockMvc.perform(get("/api/invoices").with(as(pharmacist)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/invoices/1").with(as(pharmacist)))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/invoices/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\": [{\"invoiceItemId\": 1, \"quantity\": 1}]}")
                        .with(as(pharmacist)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/invoices/1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 1.00, \"paymentMethod\": \"CASH\"}")
                        .with(as(pharmacist)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\": " + appointment.getAppointmentId() + "}")
                        .with(as(pharmacist)))
                .andExpect(status().isCreated());
    }

    @Test
    void unauthenticatedCallerGets401OnEveryInvoiceEndpoint() throws Exception {
        String body = "{\"appointmentId\": " + appointment.getAppointmentId() + "}";

        mockMvc.perform(get("/api/invoices")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/invoices/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/invoices")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/invoices/1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"items\": []}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/invoices/1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\": 1.00, \"paymentMethod\": \"CASH\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void superAdminCanGenerateAndPay() throws Exception {
        JsonNode invoice = generateInvoice(superAdmin);
        recordPayment(invoice.get("invoiceId").asLong(), "560.27");

        mockMvc.perform(get("/api/invoices/" + invoice.get("invoiceId").asLong())
                        .with(as(superAdmin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    // ------------------------------------------------------------------
    // External dispensing invoices (OTC / walk-in)
    // ------------------------------------------------------------------

    @Test
    void dispenseInvoice_billsOtcMedicinesWithoutPatientOrAppointment() throws Exception {
        ExternalDispensing event = otcEvent(cheapMedicine, 4);

        MvcResult result = mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode invoice = data(result);

        assertThat(invoice.get("patientId").isNull()).isTrue();
        assertThat(invoice.get("appointmentId").isNull()).isTrue();
        assertThat(invoice.get("dispenseId").asLong()).isEqualTo(event.getDispenseId());
        assertThat(invoice.get("status").asText()).isEqualTo("UNPAID");

        String year = String.valueOf(LocalDate.now().getYear());
        assertThat(invoice.get("invoiceNumber").asText()).matches("INV-" + year + "-\\d{6}");

        // medicines only: no consultation line
        assertThat(invoice.get("items").size()).isEqualTo(1);
        JsonNode line = invoice.get("items").get(0);
        assertThat(line.get("medicineId").asLong()).isEqualTo(cheapMedicine.getMedicineId());
        assertThat(line.get("quantity").asInt()).isEqualTo(4);
        assertThat(line.get("unitPrice").asText()).isEqualTo("0.10");
        assertThat(line.get("lineTotal").asText()).isEqualTo("0.40");
        assertThat(invoice.get("total").asText()).isEqualTo("0.40");
        assertThat(invoice.get("outstandingBalance").asText()).isEqualTo("0.40");
        assertThat(invoice.get("payments").size()).isZero();

        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void dispenseInvoice_billsWholeOtcBasketOnOneInvoice() throws Exception {
        ExternalDispensing event = otcEvent(cheapMedicine, 4);
        DispensingItem extra = new DispensingItem();
        extra.setMedicine(dearMedicine);
        extra.setQuantityDispensed(1);
        event.addItem(extra);
        event = externalDispensingRepository.save(event);

        MvcResult result = mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode invoice = data(result);

        // one event, one invoice, one line per medicine in the basket
        assertThat(invoice.get("items").size()).isEqualTo(2);
        assertThat(invoice.get("items").get(0).get("quantity").asInt()).isEqualTo(4);
        assertThat(invoice.get("items").get(1).get("medicineId").asLong())
                .isEqualTo(dearMedicine.getMedicineId());
        assertThat(invoice.get("items").get(1).get("quantity").asInt()).isEqualTo(1);
        assertThat(invoice.get("total").asText()).isEqualTo("20.39"); // 4x0.10 + 1x19.99

        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void dispenseInvoice_secondInvoiceForSameEventConflicts() throws Exception {
        ExternalDispensing event = otcEvent(dearMedicine, 1);

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void dispenseInvoice_rejectedWhenConsultationAlreadyInvoiced() throws Exception {
        ExternalDispensing event = prescriptionEvent(prescription, 1);

        generateInvoice(admin); // consultation billed first

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isConflict());

        assertThat(invoiceRepository.count()).isEqualTo(1);
    }

    @Test
    void appointmentInvoice_excludesEventsBilledOnTheirOwnInvoice() throws Exception {
        Medicine medicine = createMedicine("Amoxicillin 250mg", "10.00");
        Appointment other = createAppointment(patient, doctor,
                AppointmentStatus.COMPLETED, LocalTime.of(13, 0));
        Prescription partial = createDispensedPrescription(other, patient, doctor,
                item(medicine, 10));

        ExternalDispensing event = prescriptionEvent(partial, 3);

        // billed standalone first, while its consultation has no invoice yet
        JsonNode standalone = data(mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(standalone.get("patientId").asLong()).isEqualTo(patient.getPatientId());
        assertThat(standalone.get("appointmentId").isNull()).isTrue();
        assertThat(standalone.get("total").asText()).isEqualTo("30.00");

        // the consultation invoice must not charge those 3 units again (AC7)
        JsonNode consultation = generateInvoice(admin, other.getAppointmentId());
        assertThat(consultation.get("items").size()).isEqualTo(1); // consultation only
        assertThat(consultation.get("total").asText()).isEqualTo("500.00");
    }

    @Test
    void deferredCollection_consultationInvoicedFirstThenMedicinesOnSeparateInvoice() throws Exception {
        Appointment other = createAppointment(patient, doctor,
                AppointmentStatus.COMPLETED, LocalTime.of(15, 0));
        Prescription issued = createDispensedPrescription(other, patient, doctor,
                item(cheapMedicine, 5));
        issued.setStatus(PrescriptionStatus.ISSUED);
        prescriptionRepository.save(issued);

        // 1) consultation billed while the medicine has not been collected yet
        JsonNode consultation = generateInvoice(admin, other.getAppointmentId());
        assertThat(consultation.get("items").size()).isEqualTo(1);
        assertThat(consultation.get("total").asText()).isEqualTo("500.00");

        // 2) patient returns with the prescription and collects 3 of 5:
        // those units are billed on their own dispense invoice (AC7)
        ExternalDispensing event = prescriptionEvent(issued, 3);
        JsonNode medicines = data(mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": " + event.getDispenseId() + "}")
                        .with(as(admin)))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(medicines.get("appointmentId").isNull()).isTrue();
        assertThat(medicines.get("patientId").asLong()).isEqualTo(patient.getPatientId());
        assertThat(medicines.get("items").size()).isEqualTo(1);
        assertThat(medicines.get("items").get(0).get("quantity").asInt()).isEqualTo(3);
        assertThat(medicines.get("total").asText()).isEqualTo("0.30");

        assertThat(invoiceRepository.count()).isEqualTo(2);
    }

    @Test
    void generateInvoice_requiresExactlyOneSource() throws Exception {
        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\": 1, \"dispenseId\": 1}")
                        .with(as(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        assertThat(invoiceRepository.count()).isZero();
    }

    @Test
    void generateInvoice_unknownDispenseIdReturns404() throws Exception {
        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dispenseId\": 999999}")
                        .with(as(admin)))
                .andExpect(status().isNotFound());

        assertThat(invoiceRepository.count()).isZero();
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

    private JsonNode generateInvoice(User caller) throws Exception {
        return generateInvoice(caller, appointment.getAppointmentId());
    }

    /** Walk-in OTC event: no prescription, no patient - just the product. */
    private ExternalDispensing otcEvent(Medicine medicine, int quantity) {
        ExternalDispensing event = new ExternalDispensing();
        DispensingItem line = new DispensingItem();
        line.setMedicine(medicine);
        line.setQuantityDispensed(quantity);
        event.addItem(line);
        event.setStatus(Status.DISPENSED);
        event.setVerificationMethod("COMMON");
        event.setDispenseDate(LocalDateTime.now());
        event.setDispensedAt(LocalDateTime.now());
        event.setDeliveryMethod(DeliveryMethod.PICK_UP);
        return externalDispensingRepository.save(event);
    }

    private ExternalDispensing prescriptionEvent(Prescription prescription, int quantity) {
        ExternalDispensing event = new ExternalDispensing();
        event.setPrescription(prescription);
        PrescriptionItem prescribed = prescription.getItems().get(0);
        DispensingItem line = new DispensingItem();
        line.setPrescriptionItem(prescribed);
        line.setMedicine(prescribed.getMedicine());
        line.setQuantityDispensed(quantity);
        event.addItem(line);
        event.setStatus(Status.DISPENSED);
        event.setVerificationMethod("PRESCRIPTION_SHEET");
        event.setDispenseDate(LocalDateTime.now());
        event.setDispensedAt(LocalDateTime.now());
        event.setDeliveryMethod(DeliveryMethod.PICK_UP);
        return externalDispensingRepository.save(event);
    }

    private JsonNode generateInvoice(User caller, long appointmentId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appointmentId\": " + appointmentId + "}")
                        .with(as(caller)))
                .andExpect(status().isCreated())
                .andReturn();

        return data(result);
    }

    private JsonNode recordPayment(long invoiceId, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/invoices/" + invoiceId + "/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": " + amount + ", \"paymentMethod\": \"CASH\"}")
                        .with(as(admin)))
                .andExpect(status().isCreated())
                .andReturn();

        return data(result);
    }

    private JsonNode getInvoice(long invoiceId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoices/" + invoiceId).with(as(admin)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private JsonNode listInvoices(String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/invoices" + query).with(as(admin)))
                .andExpect(status().isOk())
                .andReturn();
        return data(result);
    }

    private JsonNode data(MvcResult result) throws Exception {
        return MONEY_JSON.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private User createUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("inv-" + role.name().toLowerCase() + "-" + unique);
        user.setEmail("inv-" + unique + "@onecare.test");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private Patient createPatient() {
        Patient patient = new Patient();
        patient.setFullName("Invoice Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 5, 12));
        patient.setContactNo("0771234567");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private Medicine createMedicine(String name, String unitPrice) {
        Medicine medicine = new Medicine();
        medicine.setName(name);
        medicine.setPrice(new BigDecimal(unitPrice));
        medicine.setCategory("Analgesic");
        medicine.setUnit("Tablet");
        medicine.setReorderLevel(10);
        medicine.setStockQuantity(100);
        medicine.setExpiryDate(LocalDate.now().plusYears(1));
        medicine.setIsQuarantined(false);
        medicine.setCreatedAt(LocalDateTime.now());
        medicine.setUpdatedAt(LocalDateTime.now());
        return medicineRepository.save(medicine);
    }

    private Appointment createAppointment(Patient patient, User doctor,
                                          AppointmentStatus status, LocalTime slot) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now());
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

    private Prescription createDispensedPrescription(Appointment appointment, Patient patient,
                                                     User doctor, PrescriptionItem... items) {
        Prescription prescription = new Prescription();
        prescription.setAppointment(appointment);
        prescription.setPatient(patient);
        prescription.setDoctor(doctor);
        prescription.setDate(LocalDate.now());
        prescription.setStatus(PrescriptionStatus.DISPENSED);
        for (PrescriptionItem item : items) {
            item.setPrescription(prescription);
            prescription.getItems().add(item);
        }
        prescription = prescriptionRepository.save(prescription);
        prescriptionItemRepository.saveAll(prescription.getItems());
        return prescription;
    }
}
