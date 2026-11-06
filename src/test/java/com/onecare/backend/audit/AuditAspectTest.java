package com.onecare.backend.audit;

import com.onecare.backend.entity.User;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.JwtService;
import com.onecare.backend.service.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditAspectTest {

    @Mock
    private AuditService auditService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtService jwtService;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature signature;

    private AuditAspect aspect;

    public static class Fixture {

        @Audited(action = "LOGIN", module = "AUTH")
        public void login(String username, String password) {
        }

        @Audited(action = "PATIENT_CREATE", module = "PATIENT")
        public Created createPatient(String name) {
            return new Created(42L);
        }
    }

    public record Created(Long patientId) {
    }

    private static Audited annotationOf(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        return Fixture.class.getMethod(methodName, parameterTypes).getAnnotation(Audited.class);
    }

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        aspect = new AuditAspect(auditService, userRepository, jwtService);
    }

    private void stubMethod(Method method, Object[] args) {
        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(joinPoint.getArgs()).thenReturn(args);
    }

    @Test
    void successRecordsActionModuleActorAndSanitizedDescription() throws Throwable {
        Audited audited = annotationOf("login", String.class, String.class);
        Method method = Fixture.class.getMethod("login", String.class, String.class);
        stubMethod(method, new Object[]{"bob", "RawSecret123!"});

        User user = new User();
        user.setUserId(7L);
        user.setUsername("bob");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        when(joinPoint.proceed()).thenReturn(null);

        aspect.auditMethod(joinPoint, audited);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(
                eq(user),
                eq("LOGIN"),
                eq("AUTH"),
                isNull(),
                description.capture(),
                isNull());

        assertThat(description.getValue())
                .contains("LOGIN outcome=SUCCESS")
                .contains("Fixture.login")
                .doesNotContain("RawSecret123!")
                .doesNotContain("bob");
    }

    @Test
    void failureRecordsFailedActionTypeAndRethrowsOriginalException() throws Throwable {
        Audited audited = annotationOf("login", String.class, String.class);
        Method method = Fixture.class.getMethod("login", String.class, String.class);
        stubMethod(method, new Object[]{"bob", "RawSecret123!"});

        User user = new User();
        user.setUserId(7L);
        user.setUsername("bob");
        when(userRepository.findByUsername("bob")).thenReturn(Optional.of(user));
        IllegalStateException failure = new IllegalStateException("boom");
        when(joinPoint.proceed()).thenThrow(failure);

        Throwable thrown = assertThrows(IllegalStateException.class,
                () -> aspect.auditMethod(joinPoint, audited));

        assertThat(thrown).isSameAs(failure);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(
                eq(user),
                eq("LOGIN_FAILED"),
                eq("AUTH"),
                isNull(),
                description.capture(),
                isNull());

        assertThat(description.getValue())
                .contains("outcome=FAILURE")
                .contains("IllegalStateException: boom")
                .doesNotContain("RawSecret123!");
    }

    @Test
    void resolvesEntityIdFromResultAccessor() throws Throwable {
        Audited audited = annotationOf("createPatient", String.class);
        Method method = Fixture.class.getMethod("createPatient", String.class);
        stubMethod(method, new Object[]{"Jan Novak"});
        when(joinPoint.proceed()).thenReturn(new Created(42L));

        aspect.auditMethod(joinPoint, audited);

        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(
                isNull(),
                eq("PATIENT_CREATE"),
                eq("PATIENT"),
                eq("42"),
                description.capture(),
                isNull());

        assertThat(description.getValue())
                .contains("PATIENT_CREATE outcome=SUCCESS")
                .doesNotContain("Jan Novak");
    }

    @Test
    void unknownActorIsRecordedAsNullUser() throws Throwable {
        Audited audited = annotationOf("login", String.class, String.class);
        Method method = Fixture.class.getMethod("login", String.class, String.class);
        stubMethod(method, new Object[]{"ghost", "whatever"});
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());
        when(joinPoint.proceed()).thenReturn(null);

        aspect.auditMethod(joinPoint, audited);

        verify(auditService).record(
                isNull(),
                eq("LOGIN"),
                eq("AUTH"),
                isNull(),
                any(),
                isNull());
    }
}
