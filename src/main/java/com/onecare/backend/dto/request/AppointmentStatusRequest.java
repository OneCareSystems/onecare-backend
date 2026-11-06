package com.onecare.backend.dto.request;

import com.onecare.backend.enums.AppointmentStatus;
import jakarta.validation.constraints.NotNull;

public record AppointmentStatusRequest(
        @NotNull(message = "Status is required") AppointmentStatus status) {
}
