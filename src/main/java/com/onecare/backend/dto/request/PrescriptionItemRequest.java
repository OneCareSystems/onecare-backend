package com.onecare.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.onecare.backend.enums.PrescriptionItemType;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One medicine line inside a CreatePrescriptionRequest.
 * - IN_HOUSE: medicineId required; validated (exists, not quarantined, not expired).
 * - EXTERNAL_  PURCHASE: medicineName required, medicineId must be null;
 *   not dispensed, not stock-checked, not invoiced.
 */
public record PrescriptionItemRequest(
        @NotNull
        PrescriptionItemType itemType,

        Long medicineId,

        @Size(max = 150)
        String medicineName,

        @NotBlank @Size(max = 50)
        String dosage,

        @NotBlank @Size(max = 100)
        String frequency,

        @NotNull @Min(1)
        Integer durationDays,

        @NotNull @Min(1)
        Integer quantity) {

        @JsonIgnore
        @AssertTrue(message = "IN_HOUSE items require medicineId (and no medicineName); "
                + "EXTERNAL_PURCHASE items require medicineName (and no medicineId)")
        public boolean isMedicineReferenceValid() {
                if (itemType == null) return true; // @NotNull already reports this
                return switch (itemType) {
                case IN_HOUSE -> medicineId != null
                        && (medicineName == null || medicineName.isBlank());
                case EXTERNAL_PURCHASE -> medicineId == null
                        && medicineName != null && !medicineName.isBlank();
                };
        }
}
