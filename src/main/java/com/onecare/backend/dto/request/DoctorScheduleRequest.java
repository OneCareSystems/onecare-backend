package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record DoctorScheduleRequest(
        @NotEmpty(message = "Weekly schedule is required")
        List<@Valid DayScheduleRequest> days
) {
}