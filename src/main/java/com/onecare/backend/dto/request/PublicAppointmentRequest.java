package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Deliberately narrower than the staff-side {@link CreateAppointmentRequest}:
 * the patient identifies themselves by name, phone and date of birth instead
 * of an internal patientId, and no internal fields (reason notes, clinical
 * notes) are accepted on this unauthenticated surface.
 */
public record PublicAppointmentRequest(
        @NotBlank(message = "Patient name is required")
        @Size(max = 100)
        String fullName,

        @NotBlank(message = "Contact number is required")
        @Size(max = 20)
        @Pattern(
                regexp = "^[\\s\\-()]*(?:(?:\\+94|0094|94|0)[\\s\\-()]*)?[1-9](?:[\\s\\-()]*\\d){8}[\\s\\-()]*$",
                message = "Contact number must be a valid Sri Lankan phone number")
        String contactNo,

        @NotNull(message = "Date of birth is required")
        @PastOrPresent(message = "Date of birth cannot be in the future")
        LocalDate dateOfBirth,

        @NotBlank(message = "Gender is required")
        @Pattern(regexp = "(?i)\\s*(MALE|FEMALE|OTHER)\\s*",
                message = "Gender must be Male, Female, or Other")
        String gender,

        @NotNull(message = "Doctor ID is required")
        @Positive(message = "Doctor ID must be positive")
        Long doctorId,

        @NotNull(message = "Appointment date/time is required")
        @Future(message = "Appointment date/time must be in the future")
        LocalDateTime appointmentDateTime) {
}
