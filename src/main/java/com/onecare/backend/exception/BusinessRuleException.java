package com.onecare.backend.exception;

/**
 * Business-rule violation (400 Bad Request), e.g. cancelling a
 * dispensed prescription, an inactive patient, a quarantined medicine
 * or a caller without the DOCTOR role trying to create a prescription.
 */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
