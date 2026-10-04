package com.onecare.backend.exception;

import java.util.ArrayList;
import java.util.List;

public class SlotConflictException extends RuntimeException {
    private final String requestedSlot;
    private final List<String> alternativeSlots;

    public SlotConflictException(String message) {
        this(message, null, new ArrayList<>());
    }

    public SlotConflictException(String message, String requestedSlot, List<String> alternativeSlots) {
        super(message);
        this.requestedSlot = requestedSlot;
        this.alternativeSlots = alternativeSlots == null ? new ArrayList<>() : new ArrayList<>(alternativeSlots);
    }

    public String getRequestedSlot() {
        return requestedSlot;
    }

    public List<String> getAlternativeSlots() {
        return alternativeSlots;
    }
}
