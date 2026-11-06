package com.onecare.backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.time.DayOfWeek;
import java.util.List;

public record DayScheduleRequest(
        @NotNull(message = "Day of week is required")
        DayOfWeek dayOfWeek,

        @NotNull(message = "Time slots are required; use an empty list for a day off")
        List<@Valid TimeSlotRequest> timeSlots
) {
}