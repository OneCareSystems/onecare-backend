package com.onecare.backend.exception;

import com.onecare.backend.dto.response.DispensePreviewResponse;

public class StockShortfallException extends ConflictException {
    private final DispensePreviewResponse preview;

    public StockShortfallException(DispensePreviewResponse preview) {
        super("Insufficient stock; confirm partial dispensing to continue");
        this.preview = preview;
    }

    public DispensePreviewResponse getPreview() { return preview; }
}
