package com.onecare.backend.audit;

import com.onecare.backend.entity.User;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.ClientIpResolver;
import com.onecare.backend.security.JwtService;
import com.onecare.backend.security.SecurityUtil;
import com.onecare.backend.service.AuditService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Writes exactly one audit record per {@link Audited} method invocation (FR041):
 * outcome {@code SUCCESS} on normal return, action type suffixed with
 * {@code _FAILED} when the method throws (the exception is rethrown unchanged).
 *
 * <p>Ordered just after {@code ExposeInvocationInterceptor}
 * ({@code HIGHEST_PRECEDENCE + 1}, required for {@code @annotation} binding)
 * and before the transaction interceptor, so the entry is recorded after the
 * business transaction completes and authorisation failures are captured too.
 *
 * <p>Never serializes argument values into the description - only method
 * identity and outcome - so passwords/JWTs/reset tokens cannot leak (NFRS-006).
 * {@link AuditSanitizer} provides a second line of defence on the text.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;
    private final UserRepository userRepository;
    private final JwtService jwtService;

    public AuditAspect(AuditService auditService, UserRepository userRepository, JwtService jwtService) {
        this.auditService = auditService;
        this.userRepository = userRepository;
        this.jwtService = jwtService;
    }

    @Around("@annotation(audited)")
    public Object auditMethod(ProceedingJoinPoint joinPoint, Audited audited) throws Throwable {
        boolean success = false;
        Throwable failure = null;
        Object result = null;
        try {
            result = joinPoint.proceed();
            success = true;
            return result;
        } catch (Throwable ex) {
            failure = ex;
            throw ex;
        } finally {
            record(audited, joinPoint, success ? "SUCCESS" : "FAILURE", result, failure);
        }
    }

    private void record(Audited audited, ProceedingJoinPoint joinPoint, String outcome,
                        Object result, Throwable failure) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Object[] args = joinPoint.getArgs();

            String actionType = "SUCCESS".equals(outcome) ? audited.action() : audited.action() + "_FAILED";

            StringBuilder description = new StringBuilder()
                    .append(audited.action()).append(" outcome=").append(outcome)
                    .append(" target=").append(method.getDeclaringClass().getSimpleName())
                    .append('.').append(method.getName());
            if (failure != null) {
                description.append(" error=").append(failure.getClass().getSimpleName());
                if (failure.getMessage() != null) {
                    description.append(": ").append(failure.getMessage());
                }
            }

            auditService.record(
                    resolveActor(method, args),
                    actionType,
                    audited.module(),
                    resolveEntityId(method, args, result),
                    description.toString(),
                    ClientIpResolver.resolveCurrentRequest());
        } catch (Exception ex) {
            log.error("Audit aspect failed | action={} | module={}", audited.action(), audited.module(), ex);
        }
    }

    /**
     * Actor of the audited action: the authenticated principal, or - for
     * pre-authentication flows such as login/refresh - the identity carried by
     * the arguments (username/email parameter, username accessor, or JWT
     * subject). The token itself is only used for the lookup, never recorded.
     */
    private User resolveActor(Method method, Object[] args) {
        Long userId = SecurityUtil.getCurrentUserId().orElse(null);
        if (userId != null) {
            return userRepository.findById(userId).orElse(null);
        }
        String identity = findIdentity(method, args);
        if (identity == null || identity.isBlank()) {
            return null;
        }
        String candidate = identity;
        return userRepository.findByUsername(candidate)
                .or(() -> userRepository.findByEmail(candidate))
                .orElse(null);
    }

    private String findIdentity(Method method, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length && i < args.length; i++) {
            String name = parameters[i].getName().toLowerCase();
            if (args[i] instanceof String value && !value.isBlank()
                    && ("username".equals(name) || "email".equals(name))) {
                return value;
            }
        }
        for (Object arg : args) {
            if (arg == null) {
                continue;
            }
            for (String accessor : new String[]{"username", "email"}) {
                if (invokeNoArgGetter(arg, accessor) instanceof String candidate && !candidate.isBlank()) {
                    return candidate;
                }
            }
            for (String accessor : new String[]{"refreshToken", "token"}) {
                if (invokeNoArgGetter(arg, accessor) instanceof String token && !token.isBlank()) {
                    String username = extractUsernameQuietly(token);
                    if (username != null) {
                        return username;
                    }
                }
            }
        }
        return null;
    }

    private String extractUsernameQuietly(String token) {
        try {
            return jwtService.extractUsername(token);
        } catch (Exception ex) {
            return null;
        }
    }

    private String resolveEntityId(Method method, Object[] args, Object result) {
        String fromResult = idFrom(result);
        if (fromResult != null) {
            return fromResult;
        }
        if (args != null) {
            Parameter[] parameters = method.getParameters();
            for (int i = 0; i < parameters.length && i < args.length; i++) {
                Object arg = args[i];
                if (arg instanceof Number number) {
                    String name = parameters[i].getName().toLowerCase();
                    if ("id".equals(name) || name.endsWith("id")) {
                        return String.valueOf(number);
                    }
                }
            }
            for (Object arg : args) {
                String id = idFrom(arg);
                if (id != null) {
                    return id;
                }
            }
        }
        return null;
    }

    /**
     * Reads an entity identifier from an object without touching anything
     * secret-shaped: only zero-arg accessors ending in {@code Id}
     * ({@code getPatientId()}, {@code patientId()}) are considered.
     */
    private String idFrom(Object target) {
        if (target == null || target instanceof String || target instanceof Number
                || target instanceof Boolean || target instanceof Date || target instanceof Enum<?>
                || target instanceof Collection<?> || target instanceof Map<?, ?>) {
            return null;
        }
        Method[] methods;
        try {
            methods = target.getClass().getMethods();
        } catch (Exception ex) {
            return null;
        }
        for (Method method : methods) {
            String name = method.getName();
            if (method.getParameterCount() != 0 || method.getReturnType() == void.class
                    || "getClass".equals(name) || !isIdAccessor(name)) {
                continue;
            }
            Object value = invokeNoArgGetter(target, name, method);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private boolean isIdAccessor(String name) {
        if ("id".equalsIgnoreCase(name)) {
            return true;
        }
        return name.length() > 2 && name.endsWith("Id");
    }

    private Object invokeNoArgGetter(Object target, String propertyName) {
        Method method;
        try {
            method = target.getClass().getMethod(propertyName);
        } catch (NoSuchMethodException ex) {
            return null;
        }
        return invokeNoArgGetter(target, propertyName, method);
    }

    private Object invokeNoArgGetter(Object target, String propertyName, Method method) {
        try {
            return method.invoke(target);
        } catch (Exception | LinkageError ex) {
            log.debug("Could not read audit value | property={} | type={}",
                    propertyName != null ? propertyName : method.getName(), target.getClass().getSimpleName());
            return null;
        }
    }
}
