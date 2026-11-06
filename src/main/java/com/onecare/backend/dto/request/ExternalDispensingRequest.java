package com.onecare.backend.dto.request;

import java.util.List;

public record ExternalDispensingRequest(
        Long prescriptionId,
        Long patientId,
        String verificationMethod,
        String deliveryMethod,
        List<ExternalDispensingItemRequest> items) {

    public ExternalDispensingRequest(Long prescriptionId, String verificationMethod, String deliveryMethod,
            List<ExternalDispensingItemRequest> items) {
        this(prescriptionId, null, verificationMethod, deliveryMethod, items);
    }
}
