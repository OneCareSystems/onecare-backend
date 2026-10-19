package com.onecare.backend.dto.response;

import java.time.DayOfWeek;
import java.util.List;

/**
 * A doctor's schedule as stored: working days and the time slots that apply
 * to each of them.
 */
public record DoctorScheduleResponse(
        Long doctorId,
        List<DayOfWeek> workingDays,
        List<ScheduleConflictResponse.TimeSlotRange> timeSlots) {
}
