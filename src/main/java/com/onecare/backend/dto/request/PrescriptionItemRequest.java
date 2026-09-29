package com.onecare.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One medicine line inside a CreatePrescriptionRequest.
 * Every medicine is validated (exists, not quarantined, not expired)
 * before any prescription data is persisted.
 */
public record PrescriptionItemRequest(
        @NotNull 
        Long medicineId,

        @NotBlank @Size(max = 50) 
        String dosage,

        @NotBlank @Size(max = 100) 
        String frequency,

        @NotNull @Min(1) 
        Integer durationDays,

        @NotNull @Min(1) 
        Integer quantity) {
}
