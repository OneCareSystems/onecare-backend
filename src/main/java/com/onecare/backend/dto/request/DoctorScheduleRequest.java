package com.onecare.backend.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.time.DayOfWeek;
import java.util.List;

/**
 * A doctor's weekly schedule: working days plus the time slots that apply to
 * each of them (FR009, UC4). Saving replaces the doctor's previous schedule.
 */
public record DoctorScheduleRequest(
        @NotEmpty(message = "At least one working day is required")
        List<DayOfWeek> workingDays,

        @NotEmpty(message = "At least one time slot is required")
        List<TimeSlotRequest> timeSlots) {
}
