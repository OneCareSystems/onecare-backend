package com.onecare.backend.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.dto.request.RecordPaymentRequest;
import com.onecare.backend.dto.request.UpdateInvoiceLineRequest;
import com.onecare.backend.dto.request.UpdateInvoiceRequest;
import com.onecare.backend.dto.response.InvoiceResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.DispensingItem;
import com.onecare.backend.entity.ExternalDispensing;
import com.onecare.backend.entity.Invoice;
import com.onecare.backend.entity.InvoiceItem;
import com.onecare.backend.entity.Medicine;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.Payment;
import com.onecare.backend.entity.Prescription;
import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.InvoiceStatus;
import com.onecare.backend.enums.PaymentMethod;
import com.onecare.backend.enums.PrescriptionItemType;
import com.onecare.backend.enums.PrescriptionStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.enums.Status;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ConflictException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.ExternalDispensingRepository;
import com.onecare.backend.repository.InvoiceRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.PrescriptionRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the DDP-23 billing rules (AC1, AC3, AC4, AC5, AC6, AC7)
 * plus the log-based audit records (DDP-25 placeholder).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvoiceServiceImplTest {

    private static final BigDecimal CONSULTATION_FEE = new BigDecimal("500.00");
    private static final long APPOINTMENT_ID = 7L;
    private static final long PATIENT_ID = 5L;
    private static final long PRESCRIPTION_ID = 9L;

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private PrescriptionRepository prescriptionRepository;
    @Mock
    private ExternalDispensingRepository externalDispensingRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private UserRepository userRepository;

    private InvoiceServiceImpl service;
    private User admin;
    private Patient patient;

    @BeforeEach
    void setUp() {
        service = new InvoiceServiceImpl(invoiceRepository, appointmentRepository,
                prescriptionRepository, externalDispensingRepository, patientRepository,
                userRepository, CONSULTATION_FEE);

        admin = user(1L, "admin", Role.ADMIN);
        patient = patient(PATIENT_ID);

        authenticate(admin);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // AC1 - generation & totals
    // ------------------------------------------------------------------

    @Test
    void generateInvoice_billsConsultationAndItemsWithCentPrecision() {
        Appointment appointment = stubCompletedAppointment();
        Medicine cheap = medicine(1L, "Cheap Medicine", "0.10");
        Medicine dear = medicine(2L, "Dear Medicine", "19.99");

        stubDispensedPrescription(appointment,
                item(100L, cheap, 3),
                item(101L, dear, 3));
        stubNoDispensingEvents();
        stubSave();

        InvoiceResponse response = service.generateInvoice(
                new GenerateInvoiceRequest(APPOINTMENT_ID, null));

        assertThat(response.invoiceNumber())
                .isEqualTo("INV-" + Year.now().getValue() + "-000042");
        assertThat(response.status()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(response.amountPaid()).isEqualByComparingTo("0.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("560.27");
        assertThat(response.createdAt()).isNotNull();
        assertThat(response.patientId()).isEqualTo(PATIENT_ID);
        assertThat(response.appointmentId()).isEqualTo(APPOINTMENT_ID);

        assertThat(response.items()).hasSize(3);

        // consultation charge: configured fee, quantity 1
        assertThat(response.items().get(0).medicineId()).isNull();
        assertThat(response.items().get(0).description()).isEqualTo("Consultation charge");
        assertThat(response.items().get(0).quantity()).isEqualTo(1);
        assertThat(response.items().get(0).unitPrice()).isEqualByComparingTo("500.00");
        assertThat(response.items().get(0).lineTotal()).isEqualByComparingTo("500.00");

        // 3 x 0.10 and 3 x 19.99 - cent-level precision, no float drift
        assertThat(response.items().get(1).unitPrice()).isEqualByComparingTo("0.10");
        assertThat(response.items().get(1).lineTotal()).isEqualByComparingTo("0.30");
        assertThat(response.items().get(2).unitPrice()).isEqualByComparingTo("19.99");
        assertThat(response.items().get(2).lineTotal()).isEqualByComparingTo("59.97");

        // total = consultation + sum(quantity x unitPrice), 2-decimal HALF_UP
        assertThat(response.total()).isEqualByComparingTo("560.27");
        assertThat(response.total().scale()).isEqualTo(2);
        assertThat(response.payments()).isEmpty();
    }

    @Test
    void generateInvoice_snapshotsTheCatalogUnitPrice() {
        Appointment appointment = stubCompletedAppointment();
        Medicine medicine = medicine(1L, "Paracetamol", "12.50");
        stubDispensedPrescription(appointment, item(100L, medicine, 2));
        stubNoDispensingEvents();
        stubSave();

        InvoiceResponse response = service.generateInvoice(
                new GenerateInvoiceRequest(APPOINTMENT_ID, null));

        assertThat(response.items().get(1).unitPrice()).isEqualByComparingTo("12.50");
        assertThat(response.items().get(1).lineTotal()).isEqualByComparingTo("25.00");
        assertThat(response.total()).isEqualByComparingTo("525.00");

        ArgumentCaptor<Invoice> saved = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).saveAndFlush(saved.capture());

        // the catalog price changes after the invoice was generated...
        medicine.setPrice(new BigDecimal("99.99"));
        when(invoiceRepository.findById(saved.getValue().getInvoiceId()))
                .thenReturn(Optional.of(saved.getValue()));

        // ...viewing the invoice again still shows the locked snapshot
        InvoiceResponse viewed =
                service.findInvoiceById(saved.getValue().getInvoiceId());

        assertThat(viewed.items().get(1).unitPrice()).isEqualByComparingTo("12.50");
        assertThat(viewed.items().get(1).lineTotal()).isEqualByComparingTo("25.00");
        assertThat(viewed.total()).isEqualByComparingTo("525.00");
    }

    @Test
    void generateInvoice_withoutPrescriptionBillsConsultationOnly() {
        Appointment appointment = stubCompletedAppointment();
        when(prescriptionRepository.findByAppointmentAppointmentId(APPOINTMENT_ID))
                .thenReturn(Optional.empty());
        stubSave();

        InvoiceResponse response = service.generateInvoice(
                new GenerateInvoiceRequest(APPOINTMENT_ID, null));

        assertThat(response.items()).hasSize(1);
        assertThat(response.total()).isEqualByComparingTo("500.00");
        assertThat(response.status()).isEqualTo(InvoiceStatus.UNPAID);
    }

    // ------------------------------------------------------------------
    // AC7 - no double billing & only dispensed quantities
    // ------------------------------------------------------------------

    @Test
    void generateInvoice_rejectsSecondInvoiceForSameConsultation() {
        Appointment appointment = stubCompletedAppointment();
        when(invoiceRepository.existsByAppointmentAppointmentId(APPOINTMENT_ID)).thenReturn(true);

        ConflictException exception = assertThrows(ConflictException.class,
                () -> service.generateInvoice(new GenerateInvoiceRequest(APPOINTMENT_ID, null)));

        assertThat(exception.getMessage()).contains("already exists");
        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void generateInvoice_billsOnlyDispensedQuantityOfPartiallyDispensedItem() {
        Appointment appointment = stubCompletedAppointment();
        Medicine medicine = medicine(1L, "Amoxicillin", "10.00");
        PrescriptionItem prescribed = item(100L, medicine, 10);

        Prescription prescription = stubDispensedPrescription(appointment, prescribed);

        // the pharmacist only handed out 3 of the 10 prescribed units
        ExternalDispensing event = new ExternalDispensing();
        event.setPrescription(prescription);
        DispensingItem handover = new DispensingItem();
        handover.setPrescriptionItem(prescribed);
        handover.setMedicine(medicine);
        handover.setQuantityDispensed(3);
        event.addItem(handover);
        event.setStatus(Status.DISPENSED);
        when(externalDispensingRepository
                .findByPrescription_PrescriptionIdAndStatus(PRESCRIPTION_ID, Status.DISPENSED))
                .thenReturn(List.of(event));

        stubSave();

        InvoiceResponse response = service.generateInvoice(
                new GenerateInvoiceRequest(APPOINTMENT_ID, null));

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(1).quantity()).isEqualTo(3);
        assertThat(response.items().get(1).lineTotal()).isEqualByComparingTo("30.00");
        assertThat(response.total()).isEqualByComparingTo("530.00");
    }

    @Test
    void generateInvoice_neverBillsExternallyDispensedItems() {
        Appointment appointment = stubCompletedAppointment();
        Medicine medicine = medicine(1L, "Paracetamol", "5.00");

        Prescription prescription = new Prescription();
        prescription.setPrescriptionId(PRESCRIPTION_ID);
        prescription.setAppointment(appointment);
        prescription.setPatient(patient);
        prescription.setStatus(PrescriptionStatus.DISPENSED);

        PrescriptionItem external = new PrescriptionItem();
        external.setItemId(100L);
        external.setPrescription(prescription);
        external.setItemType(PrescriptionItemType.EXTERNAL_PURCHASE);
        external.setMedicineName("Bandages (external)");
        external.setQuantity(2);
        prescription.getItems().add(external);

        PrescriptionItem inHouse = item(101L, medicine, 1);
        inHouse.setPrescription(prescription);
        prescription.getItems().add(inHouse);

        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        when(invoiceRepository.existsByAppointmentAppointmentId(APPOINTMENT_ID)).thenReturn(false);
        when(prescriptionRepository.findByAppointmentAppointmentId(APPOINTMENT_ID))
                .thenReturn(Optional.of(prescription));
        stubNoDispensingEvents();
        stubSave();

        InvoiceResponse response = service.generateInvoice(
                new GenerateInvoiceRequest(APPOINTMENT_ID, null));

        assertThat(response.items()).hasSize(2); // consultation + in-house only
        assertThat(response.items())
                .extracting("description")
                .doesNotContain("Bandages (external)");
    }

    @Test
    void generateInvoice_rejectsConsultationThatIsNotCompleted() {
        Appointment appointment = appointment(APPOINTMENT_ID, patient, AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));

        assertThrows(BusinessRuleException.class,
                () -> service.generateInvoice(new GenerateInvoiceRequest(APPOINTMENT_ID, null)));

        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void generateInvoice_whenPrescriptionNotDispensedBillsConsultationOnly() {
        Appointment appointment = stubCompletedAppointment();
        Prescription prescription = new Prescription();
        prescription.setPrescriptionId(PRESCRIPTION_ID);
        prescription.setAppointment(appointment);
        prescription.setPatient(patient);
        prescription.setStatus(PrescriptionStatus.ISSUED);

        when(prescriptionRepository.findByAppointmentAppointmentId(APPOINTMENT_ID))
                .thenReturn(Optional.of(prescription));
        stubSave();

        // patient collects later: consultation charge now, medicine is
        // billed on per-event dispense invoices when it is handed out
        InvoiceResponse response = service.generateInvoice(
                new GenerateInvoiceRequest(APPOINTMENT_ID, null));

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).medicineId()).isNull();
        assertThat(response.items().get(0).description()).isEqualTo("Consultation charge");
        assertThat(response.total()).isEqualByComparingTo("500.00");
        assertThat(response.status()).isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void generateInvoice_rejectsUnknownAppointment() {
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> service.generateInvoice(new GenerateInvoiceRequest(APPOINTMENT_ID, null)));
    }

    // ------------------------------------------------------------------
    // AC4 / AC5 - partial & full payment
    // ------------------------------------------------------------------

    @Test
    void recordPayment_partialPayment_keepsInvoiceUnpaidAndRecordsPayment() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "600.00", "0.00", consultationLine(), medicineLine(100L, medicine(1L, "Paracetamol", "10.00"), 10, "100.00"));
        stubInvoice(invoice);
        stubSave();

        InvoiceResponse response = service.recordPayment(42L,
                new RecordPaymentRequest(new BigDecimal("150.00"), PaymentMethod.CASH));

        assertThat(response.amountPaid()).isEqualByComparingTo("150.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("450.00");
        assertThat(response.status()).isEqualTo(InvoiceStatus.UNPAID);
        assertThat(response.payments()).hasSize(1);
        assertThat(response.payments().get(0).amount()).isEqualByComparingTo("150.00");
        assertThat(response.payments().get(0).paymentMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(response.payments().get(0).recordedBy()).isEqualTo(1L);
        assertThat(response.payments().get(0).paidAt()).isNotNull();
    }

    @Test
    void recordPayment_fullPayment_setsAmountPaidAndStatusPaid() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "500.00", "0.00", consultationLine());
        stubInvoice(invoice);
        stubSave();

        InvoiceResponse response = service.recordPayment(42L,
                new RecordPaymentRequest(new BigDecimal("500.00"), PaymentMethod.CASH));

        assertThat(response.amountPaid()).isEqualByComparingTo(response.total());
        assertThat(response.amountPaid()).isEqualByComparingTo("500.00");
        assertThat(response.outstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(response.status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(response.payments()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // AC6 - invalid payment amounts
    // ------------------------------------------------------------------

    @Test
    void recordPayment_rejectsOverpaymentAndLeavesInvoiceUntouched() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "500.00", "0.00", consultationLine());
        stubInvoice(invoice);

        assertThrows(BusinessRuleException.class, () -> service.recordPayment(42L,
                new RecordPaymentRequest(new BigDecimal("500.01"), PaymentMethod.CASH)));

        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(invoice.getPayments()).isEmpty();
        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.UNPAID);
        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void recordPayment_rejectsZeroAndNegativeAmounts() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "500.00", "0.00", consultationLine());
        stubInvoice(invoice);

        assertThrows(BusinessRuleException.class, () -> service.recordPayment(42L,
                new RecordPaymentRequest(BigDecimal.ZERO, PaymentMethod.CASH)));
        assertThrows(BusinessRuleException.class, () -> service.recordPayment(42L,
                new RecordPaymentRequest(new BigDecimal("-10.00"), PaymentMethod.CASH)));

        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(invoice.getPayments()).isEmpty();
        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void recordPayment_rejectsAmountWithMoreThanTwoDecimals() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "500.00", "0.00", consultationLine());
        stubInvoice(invoice);

        assertThrows(BusinessRuleException.class, () -> service.recordPayment(42L,
                new RecordPaymentRequest(new BigDecimal("10.999"), PaymentMethod.CASH)));

        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("0.00");
        assertThat(invoice.getPayments()).isEmpty();
        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void recordPayment_rejectsPaymentOnPaidInvoice() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.PAID,
                "500.00", "500.00", consultationLine());
        stubInvoice(invoice);

        assertThrows(BusinessRuleException.class, () -> service.recordPayment(42L,
                new RecordPaymentRequest(new BigDecimal("10.00"), PaymentMethod.CASH)));

        assertThat(invoice.getPayments()).isEmpty();
        assertThat(invoice.getAmountPaid()).isEqualByComparingTo("500.00");
        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void recordPayment_rejectsUnknownInvoice() {
        when(invoiceRepository.findById(404L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> service.recordPayment(404L,
                new RecordPaymentRequest(new BigDecimal("10.00"), PaymentMethod.CASH)));
    }

    // ------------------------------------------------------------------
    // AC3 - line correction
    // ------------------------------------------------------------------

    @Test
    void updateInvoiceLines_recalculatesTotalAndKeepsConsultationLine() {
        Medicine medicine = medicine(1L, "Paracetamol", "10.00");
        InvoiceItem lineA = medicineLine(100L, medicine, 3, "30.00");
        InvoiceItem lineB = medicineLine(101L, medicine, 2, "20.00");

        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "550.00", "0.00", consultationLine(), lineA, lineB);
        stubInvoice(invoice);
        stubSave();

        // keep line A with quantity 1, drop line B entirely
        UpdateInvoiceRequest request = new UpdateInvoiceRequest(
                List.of(new UpdateInvoiceLineRequest(100L, 1)));

        InvoiceResponse response = service.updateInvoiceLines(42L, request);

        assertThat(response.items()).hasSize(2); // consultation + line A
        assertThat(response.items().get(0).description()).isEqualTo("Consultation charge");
        assertThat(response.items().get(1).invoiceItemId()).isEqualTo(100L);
        assertThat(response.items().get(1).quantity()).isEqualTo(1);
        assertThat(response.items().get(1).unitPrice()).isEqualByComparingTo("10.00");
        assertThat(response.total()).isEqualByComparingTo("510.00");
        assertThat(response.status()).isEqualTo(InvoiceStatus.UNPAID);
    }

    @Test
    void updateInvoiceLines_rejectsTotalBelowAmountPaid() {
        Medicine medicine = medicine(1L, "Paracetamol", "10.00");
        InvoiceItem lineA = medicineLine(100L, medicine, 3, "30.00");
        InvoiceItem lineB = medicineLine(101L, medicine, 2, "20.00");

        // 500 consultation + 50 of items, 535 already collected
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "550.00", "535.00", consultationLine(), lineA, lineB);
        stubInvoice(invoice);

        UpdateInvoiceRequest request = new UpdateInvoiceRequest(
                List.of(new UpdateInvoiceLineRequest(100L, 1)));

        assertThrows(BusinessRuleException.class,
                () -> service.updateInvoiceLines(42L, request));

        // nothing changed: quantities, lines and totals are untouched
        assertThat(invoice.getItems()).hasSize(3);
        assertThat(lineA.getQuantity()).isEqualTo(3);
        assertThat(invoice.getTotal()).isEqualByComparingTo("550.00");
        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void updateInvoiceLines_rejectsEditOfPaidInvoice() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.PAID,
                "550.00", "550.00", consultationLine(),
                medicineLine(100L, medicine(1L, "Paracetamol", "10.00"), 3, "30.00"));
        stubInvoice(invoice);

        UpdateInvoiceRequest request = new UpdateInvoiceRequest(
                List.of(new UpdateInvoiceLineRequest(100L, 1)));

        assertThrows(BusinessRuleException.class,
                () -> service.updateInvoiceLines(42L, request));

        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    @Test
    void updateInvoiceLines_rejectsLineOfAnotherInvoice() {
        Invoice invoice = invoice(42L, "INV-2026-000042", InvoiceStatus.UNPAID,
                "550.00", "0.00", consultationLine(),
                medicineLine(100L, medicine(1L, "Paracetamol", "10.00"), 3, "30.00"));
        stubInvoice(invoice);

        UpdateInvoiceRequest request = new UpdateInvoiceRequest(
                List.of(new UpdateInvoiceLineRequest(999L, 1)));

        assertThrows(BusinessRuleException.class,
                () -> service.updateInvoiceLines(42L, request));

        verify(invoiceRepository, never()).saveAndFlush(any(Invoice.class));
    }

    // ------------------------------------------------------------------
    // Audit (DDP-25 placeholder: exactly one record per domain action)
    // ------------------------------------------------------------------

    @Test
    void emitsExactlyOneAuditRecordForGenerateEditAndPay() {
        Logger logger = (Logger) LoggerFactory.getLogger(InvoiceServiceImpl.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            Appointment appointment = stubCompletedAppointment();
            stubDispensedPrescription(appointment, item(100L, medicine(1L, "Paracetamol", "10.00"), 2));
            stubNoDispensingEvents();
            stubSave();

            InvoiceResponse generated = service.generateInvoice(
                    new GenerateInvoiceRequest(APPOINTMENT_ID, null));

            when(invoiceRepository.findById(generated.invoiceId()))
                    .thenReturn(Optional.of(reconstruct(generated)));

            service.updateInvoiceLines(generated.invoiceId(), new UpdateInvoiceRequest(
                    List.of(new UpdateInvoiceLineRequest(
                            generated.items().get(1).invoiceItemId(), 1))));

            service.recordPayment(generated.invoiceId(),
                    new RecordPaymentRequest(new BigDecimal("510.00"), PaymentMethod.CASH));
        } finally {
            logger.detachAppender(appender);
        }

        List<String> auditRecords = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(auditRecords).hasSize(3);
        assertThat(auditRecords.get(0)).contains("Invoice generated");
        assertThat(auditRecords.get(1)).contains("Invoice line edited");
        assertThat(auditRecords.get(2)).contains("Payment recorded");
        assertThat(auditRecords).allSatisfy(record -> assertThat(record).contains("performedBy=admin"));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void authenticate(User user) {
        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(), user.getUsername(), user.getPasswordHash(),
                true, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(),
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }

    private User user(Long id, String username, Role role) {
        User user = new User();
        user.setUserId(id);
        user.setUsername(username);
        user.setEmail(username + "@onecare.test");
        user.setPasswordHash("hash");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return user;
    }

    private Patient patient(Long id) {
        Patient patient = new Patient();
        patient.setPatientId(id);
        patient.setFullName("Kasun Fernando");
        patient.setIsActive(true);
        return patient;
    }

    private Appointment appointment(Long id, Patient patient, AppointmentStatus status) {
        Appointment appointment = new Appointment();
        appointment.setAppointmentId(id);
        appointment.setPatient(patient);
        appointment.setStatus(status);
        return appointment;
    }

    private Medicine medicine(Long id, String name, String unitPrice) {
        Medicine medicine = new Medicine();
        medicine.setMedicineId(id);
        medicine.setName(name);
        medicine.setPrice(new BigDecimal(unitPrice));
        return medicine;
    }

    private PrescriptionItem item(Long id, Medicine medicine, int quantity) {
        PrescriptionItem item = new PrescriptionItem();
        item.setItemId(id);
        item.setItemType(PrescriptionItemType.IN_HOUSE);
        item.setMedicine(medicine);
        item.setQuantity(quantity);
        return item;
    }

    private Appointment stubCompletedAppointment() {
        Appointment appointment = appointment(APPOINTMENT_ID, patient, AppointmentStatus.COMPLETED);
        when(appointmentRepository.findById(APPOINTMENT_ID)).thenReturn(Optional.of(appointment));
        when(invoiceRepository.existsByAppointmentAppointmentId(APPOINTMENT_ID)).thenReturn(false);
        return appointment;
    }

    private Prescription stubDispensedPrescription(Appointment appointment, PrescriptionItem... items) {
        Prescription prescription = new Prescription();
        prescription.setPrescriptionId(PRESCRIPTION_ID);
        prescription.setAppointment(appointment);
        prescription.setPatient(patient);
        prescription.setStatus(PrescriptionStatus.DISPENSED);
        for (PrescriptionItem item : items) {
            item.setPrescription(prescription);
            prescription.getItems().add(item);
        }
        when(prescriptionRepository.findByAppointmentAppointmentId(APPOINTMENT_ID))
                .thenReturn(Optional.of(prescription));
        return prescription;
    }

    private void stubNoDispensingEvents() {
        when(externalDispensingRepository
                .findByPrescription_PrescriptionIdAndStatus(PRESCRIPTION_ID, Status.DISPENSED))
                .thenReturn(List.of());
    }

    private void stubInvoice(Invoice invoice) {
        when(invoiceRepository.findById(invoice.getInvoiceId())).thenReturn(Optional.of(invoice));
    }

    /** Assigns the generated id (42) the way a real INSERT would. */
    private void stubSave() {
        when(invoiceRepository.saveAndFlush(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getInvoiceId() == null) {
                invoice.setInvoiceId(42L);
            }
            // @CreationTimestamp is applied by Hibernate on persist
            if (invoice.getCreatedAt() == null) {
                invoice.setCreatedAt(LocalDateTime.now());
            }
            long itemId = 100L;
            for (InvoiceItem item : invoice.getItems()) {
                if (item.getInvoiceItemId() == null) {
                    item.setInvoiceItemId(itemId++);
                }
            }
            for (Payment payment : invoice.getPayments()) {
                if (payment.getPaidAt() == null) {
                    payment.setPaidAt(LocalDateTime.now());
                }
            }
            return invoice;
        });
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(invocation ->
                invocation.getArgument(0));
    }

    private InvoiceItem consultationLine() {
        InvoiceItem line = new InvoiceItem();
        line.setInvoiceItemId(1L);
        line.setDescription("Consultation charge");
        line.setQuantity(1);
        line.setUnitPrice(CONSULTATION_FEE);
        line.setLineTotal(CONSULTATION_FEE);
        return line;
    }

    private InvoiceItem medicineLine(Long id, Medicine medicine, int quantity, String lineTotal) {
        InvoiceItem line = new InvoiceItem();
        line.setInvoiceItemId(id);
        line.setMedicine(medicine);
        line.setDescription(medicine.getName());
        line.setQuantity(quantity);
        line.setUnitPrice(medicine.getPrice());
        line.setLineTotal(new BigDecimal(lineTotal));
        return line;
    }

    private Invoice invoice(Long id, String number, InvoiceStatus status,
                            String total, String amountPaid, InvoiceItem... lines) {
        Invoice invoice = new Invoice();
        invoice.setInvoiceId(id);
        invoice.setInvoiceNumber(number);
        invoice.setPatient(patient);
        invoice.setAppointment(appointment(APPOINTMENT_ID, patient, AppointmentStatus.COMPLETED));
        invoice.setStatus(status);
        invoice.setTotal(new BigDecimal(total));
        invoice.setAmountPaid(new BigDecimal(amountPaid));
        invoice.setCreatedBy(admin);
        invoice.setItems(new ArrayList<>(List.of(lines)));
        invoice.setPayments(new ArrayList<>());
        for (InvoiceItem line : lines) {
            line.setInvoice(invoice);
        }
        return invoice;
    }

    /** Rebuilds the invoice a response was mapped from, for chained actions. */
    private Invoice reconstruct(InvoiceResponse response) {
        List<InvoiceItem> lines = new ArrayList<>();
        for (var item : response.items()) {
            InvoiceItem line = new InvoiceItem();
            line.setInvoiceItemId(item.invoiceItemId());
            line.setDescription(item.description());
            line.setQuantity(item.quantity());
            line.setUnitPrice(item.unitPrice());
            line.setLineTotal(item.lineTotal());
            if (item.medicineId() != null) {
                line.setMedicine(medicine(item.medicineId(), item.description(),
                        item.unitPrice().toPlainString()));
            }
            lines.add(line);
        }
        return invoice(response.invoiceId(), response.invoiceNumber(), response.status(),
                response.total().toPlainString(), response.amountPaid().toPlainString(),
                lines.toArray(new InvoiceItem[0]));
    }
}
