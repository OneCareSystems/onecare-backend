package com.onecare.backend.dto.request;

public record VerifyExternalDispensingRequest(
        String verificationMethod,
        Boolean verified,
        String notes) {

    public VerifyExternalDispensingRequest() {
        this(null, true, null);
    }

    public VerifyExternalDispensingRequest(String verificationMethod) {
        this(verificationMethod, true, null);
    }
}
