package com.onecare.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AppointmentStatusRequest(
        @NotBlank(message = "Status is required") @Pattern(regexp = "Scheduled|Completed|Cancelled|No-show", message = "Status must be one of: Scheduled, Completed, Cancelled, No-show") String status) {
}
