package com.onecare.backend.enums;

/**
 * Payment methods accepted by the billing APIs (BROII cash-only rule).
 *
 * CASH is the only constant: the enum itself IS the cash-only rule, so a
 * request carrying any other method fails JSON deserialization with 400 and
 * never reaches the domain - no payment or invoice state is ever modified.
 */
public enum PaymentMethod {
    CASH
}
