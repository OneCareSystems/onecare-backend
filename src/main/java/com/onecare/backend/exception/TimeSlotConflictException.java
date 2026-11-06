package com.onecare.backend.exception;

import com.onecare.backend.dto.response.ScheduleConflictResponse;

/**
 * Submitted doctor-schedule time slots overlap each other (UC4 alt flow).
 * Mapped to 400 with the exact message "Time slot conflict" and the
 * conflicting ranges in data.conflictingSlots.
 */
public class TimeSlotConflictException extends RuntimeException {

    private final transient ScheduleConflictResponse conflictingSlots;

    public TimeSlotConflictException(ScheduleConflictResponse conflictingSlots) {
        super("Time slot conflict");
        this.conflictingSlots = conflictingSlots;
    }

    public ScheduleConflictResponse getConflictingSlots() {
        return conflictingSlots;
    }
}
