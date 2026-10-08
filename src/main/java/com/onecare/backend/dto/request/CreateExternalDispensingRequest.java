package com.onecare.backend.dto.request;

import java.util.List;

public record CreateExternalDispensingRequest(
        Long prescriptionId,
        Long patientId,
        String verificationMethod,
        String deliveryMethod,
        List<ExternalDispensingItemRequest> items
) {

    public CreateExternalDispensingRequest(Long prescriptionId, String verificationMethod, String deliveryMethod,
                                          List<ExternalDispensingItemRequest> items) {
        this(prescriptionId, null, verificationMethod, deliveryMethod, items);
    }

    public CreateExternalDispensingRequest(Long patientId, String verificationMethod, String deliveryMethod,
                                          Long medicineId, Integer quantity) {
        this(null, patientId, verificationMethod, deliveryMethod,
                List.of(new ExternalDispensingItemRequest(medicineId, quantity)));
    }

    public CreateExternalDispensingRequest(String verificationMethod, String deliveryMethod,
                                          List<ExternalDispensingItemRequest> items) {
        this(null, null, verificationMethod, deliveryMethod, items);
    }
}
