package com.onecare.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * One time slot of a doctor's schedule, "HH:mm" start and end.
 */
public record TimeSlotRequest(
        @NotBlank(message = "Slot start time is required")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Slot start must be in HH:mm format")
        String start,

        @NotBlank(message = "Slot end time is required")
        @Pattern(regexp = "^([01]\\d|2[0-3]):[0-5]\\d$", message = "Slot end must be in HH:mm format")
        String end) {
}
