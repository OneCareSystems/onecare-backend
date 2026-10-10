package com.onecare.backend.integration;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Gender;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.SlotConflictException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PasswordResetTokenRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.AppUserDetailsService;
import com.onecare.backend.service.AppointmentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class AppointmentConcurrencyIntegrationTest {

    @Autowired
    private AppointmentServiceImpl appointmentService;

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @BeforeEach
    void setUp() {
        appointmentRepository.deleteAll();
        patientRepository.deleteAll();
        passwordResetTokenRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void concurrentCreates_forSameDoctorAndSlot_allowOnlyOneBooking() throws Exception {
        User admin = createUser("admin", "admin@onecare.com", Role.ADMIN);
        User doctor = createUser("doctor", "doctor@onecare.com", Role.DOCTOR);

        Patient patient = new Patient();
        patient.setFullName("Concurrent Patient");
        patient.setDateOfBirth(java.time.LocalDate.of(1990, 1, 1));
        patient.setContactNo("0771111222");
        patient.setEmail("patient@onecare.com");
        patient.setGender(Gender.MALE);
        patient.setIsActive(true);
        patient = patientRepository.save(patient);

        LocalDateTime slot = LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        CreateAppointmentRequest request = new CreateAppointmentRequest(doctor.getUserId(), patient.getPatientId(), slot, "checkup", null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);

        Callable<Object> task = () -> {
            authenticate(admin, "ROLE_ADMIN");
            startGate.await(5, TimeUnit.SECONDS);
            try {
                return appointmentService.createAppointment(request);
            } finally {
                SecurityContextHolder.clearContext();
            }
        };

        Future<Object> first = executor.submit(task);
        Future<Object> second = executor.submit(task);
        startGate.countDown();

        List<Object> results = List.of(first, second).stream().map(future -> {
            try {
                return future.get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                return exception.getCause() != null ? exception.getCause() : exception;
            }
        }).collect(Collectors.toList());

        executor.shutdownNow();

        long successes = results.stream().filter(result -> !(result instanceof Throwable)).count();
        long conflicts = results.stream().filter(result -> result instanceof SlotConflictException).count();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(appointmentRepository.findAll()).hasSize(1);
    }

    private User createUser(String username, String email, Role role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash("$argon2id$test");
        user.setRole(role);
        user.setIsActive(true);
        user.setFailedAttempts(0);
        return userRepository.save(user);
    }

    private void authenticate(User user, String authority) {
        var principal = new AppUserDetailsService.AppUserDetails(
                user.getUserId(),
                user.getUsername(),
                user.getPasswordHash(),
                true,
                true,
                true,
                true,
                List.of(new SimpleGrantedAuthority(authority)));

        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                user.getPasswordHash(),
                List.of(new SimpleGrantedAuthority(authority)));

        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
