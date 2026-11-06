package com.onecare.backend.dto.response;

import java.util.List;

/**
 * Payload of the 400 response returned when a doctor schedule is saved with
 * overlapping time slots (UC4 alt flow). Each range is an already-overlapping
 * pair of the submitted slots, in "HH:mm" form.
 */
public record ScheduleConflictResponse(List<TimeSlotRange> conflictingSlots) {

    public record TimeSlotRange(String start, String end) {
    }
}
