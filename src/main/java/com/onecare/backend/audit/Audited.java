package com.onecare.backend.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose completion must produce exactly one audit
 * trail record (FR041, UC26).
 *
 * <p>The {@link AuditAspect} writes a single entry per invocation:
 * outcome SUCCESS when the method returns normally, or outcome FAILURE
 * (action type suffixed with {@code _FAILED}) when it throws - the exception
 * is always rethrown, so business behaviour is unchanged.
 *
 * <p>{@code entityType} of the record is set to {@code module}, which is what
 * the {@code module} filter on GET /api/audit-logs matches against.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Action type recorded for the event, e.g. {@code PATIENT_CREATE}. */
    String action();

    /** Owning module, e.g. {@code PATIENT}, {@code AUTH}, {@code INVOICE}. */
    String module();

    /** Optional method parameter carrying the entity ID when the result has several IDs. */
    String entityIdParameter() default "";
}
