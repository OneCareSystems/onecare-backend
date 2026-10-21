package com.onecare.backend.dto.response;

import com.onecare.backend.dto.response.ScheduleConflictResponse.TimeSlotRange;

import java.time.DayOfWeek;
import java.util.List;

public record DayScheduleResponse(
        DayOfWeek dayOfWeek,
        List<TimeSlotRange> timeSlots
) {
}