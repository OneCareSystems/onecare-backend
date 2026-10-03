package com.onecare.backend.dto.response;

import java.util.List;

public record SlotConflictResponse(
        String message,
        String requestedSlot,
        List<String> alternativeSlots) {
}
