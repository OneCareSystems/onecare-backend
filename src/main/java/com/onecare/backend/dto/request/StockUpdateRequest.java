package com.onecare.backend.dto.request;

import jakarta.validation.constraints.NotNull;

public record StockUpdateRequest(

        @NotNull(message = "Stock delta is required")
        Integer delta
) {
}