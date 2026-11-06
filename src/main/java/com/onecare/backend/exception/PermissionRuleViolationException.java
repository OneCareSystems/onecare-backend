package com.onecare.backend.exception;

/**
 * A role-permission change violates one of the hard business rules
 * BR004-BR006. Mapped to 422 with a message naming the violated rule;
 * nothing is persisted when this is thrown.
 */
public class PermissionRuleViolationException extends RuntimeException {

    public PermissionRuleViolationException(String message) {
        super(message);
    }
}
