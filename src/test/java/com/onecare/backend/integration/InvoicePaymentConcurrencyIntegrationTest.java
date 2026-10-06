package com.onecare.backend.integration;

import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.dto.request.RecordPaymentRequest;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Invoice;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.InvoiceStatus;
import com.onecare.backend.enums.PaymentMethod;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.InvoiceRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.service.InvoiceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DDP-23: two simultaneous cash payments must never overpay an invoice.
 * The loser either fails the optimistic lock (HTTP 409) or sees the invoice
 * already settled (HTTP 400) - either way its transaction rolls back and the
 * invoice records exactly one payment (AC4/AC5).
 */
@SpringBootTest
class InvoicePaymentConcurrencyIntegrationTest {

    @Autowired
    private InvoiceService invoiceService;
    @Autowired
    private InvoiceRepository invoiceRepository;
    @Autowired
    private AppointmentRepository appointmentRepository;
    @Autowired
    private PatientRepository patientRepository;
    @Autowired
    private UserRepository userRepository;

    private User admin;

    @BeforeEach
    void setUp() {
        // this class is not transactional, so start from a clean billing log
        invoiceRepository.deleteAll();
        admin = createUser(Role.ADMIN);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
        // other suites delete appointments/users; invoices would block that (FK)
        invoiceRepository.deleteAll();
    }

    @Test
    void concurrentFullPayments_recordExactlyOneAndNeverOverpay() throws Exception {
        User doctor = createUser(Role.DOCTOR);
        Patient patient = createPatient();
        Appointment appointment = createAppointment(patient, doctor);

        authenticate(admin);
        var generated = invoiceService.generateInvoice(
                new GenerateInvoiceRequest(appointment.getAppointmentId(), null));
        SecurityContextHolder.clearContext();

        Long invoiceId = generated.invoiceId();
        assertThat(generated.total()).isEqualByComparingTo("500.00");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<Object> fullPayment = () -> {
            authenticate(admin);
            startGate.await(5, TimeUnit.SECONDS);
            try {
                return invoiceService.recordPayment(invoiceId,
                        new RecordPaymentRequest(generated.total(), PaymentMethod.CASH));
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        Future<Object> first = executor.submit(fullPayment);
        Future<Object> second = executor.submit(fullPayment);
        startGate.countDown();

        List<Object> results = new ArrayList<>();
        for (Future<Object> future : List.of(first, second)) {
            try {
                results.add(future.get(15, TimeUnit.SECONDS));
            } catch (Exception exception) {
                results.add(exception.getCause() != null ? exception.getCause() : exception);
            }
        }
        executor.shutdownNow();

        List<Object> successes = results.stream()
                .filter(result -> !(result instanceof Throwable))
                .toList();
        List<Throwable> failures = results.stream()
                .filter(result -> result instanceof Throwable)
                .map(result -> (Throwable) result)
                .toList();

        assertThat(successes).hasSize(1);
        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOfAny(
                OptimisticLockingFailureException.class, BusinessRuleException.class);

        // the losing transaction rolled back: no overpay, no phantom payment
        Invoice settled = invoiceRepository.findById(invoiceId).orElseThrow();
        assertThat(settled.getTotal()).isEqualByComparingTo(generated.total());
        assertThat(settled.getAmountPaid()).isEqualByComparingTo(generated.total());
        assertThat(settled.getOutstandingBalance()).isEqualByComparingTo("0.00");
        assertThat(settled.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(settled.getPayments()).hasSize(1);
    }

    private User createUser(Role role) {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        User user = new User();
        user.setUsername("invcon-" + role.name().toLowerCase() + "-" + unique);
        user.setEmail("invcon-" + unique + "@onecare.test");
        user.setPasswordHash("$argon2id$test");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private Patient createPatient() {
        Patient patient = new Patient();
        patient.setFullName("Concurrent Invoice Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setContactNo("0771234000");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        return patientRepository.save(patient);
    }

    private Appointment createAppointment(Patient patient, User doctor) {
        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(LocalDate.now());
        appointment.setTimeSlot(LocalTime.of(9, 30));
        appointment.setStatus(AppointmentStatus.COMPLETED);
        appointment.setReason("Billing concurrency checkup");
        return appointmentRepository.save(appointment);
    }

    private void authenticate(User user) {
        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(), user.getUsername(), user.getPasswordHash(),
                true, true, true, true,
                List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, user.getPasswordHash(),
                        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()))));
    }
}
