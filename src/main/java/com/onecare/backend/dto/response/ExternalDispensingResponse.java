package com.onecare.backend.dto.response;

import com.onecare.backend.entity.DispensingItem;
import com.onecare.backend.entity.ExternalDispensing;
import com.onecare.backend.enums.DeliveryMethod;
import com.onecare.backend.enums.Status;

import java.time.LocalDateTime;
import java.util.List;

public record ExternalDispensingResponse(
        Long dispenseId,
        Long prescriptionId,
        Long patientId,
        String verificationMethod,
        String verificationStatus,
        Status status,
        DeliveryMethod deliveryMethod,
        LocalDateTime dispenseDate,
        LocalDateTime dispensedAt,
        LocalDateTime verifiedAt,
        Integer retryCount,
        Long auditLogId,
        List<ExternalDispensingItemResponse> items
) {

    public static ExternalDispensingResponse from(ExternalDispensing entity) {
        if (entity == null) {
            return null;
        }

        return new ExternalDispensingResponse(
                entity.getDispenseId(),
                entity.getPrescription() != null ? entity.getPrescription().getPrescriptionId() : null,
                entity.getPatientId(),
                entity.getVerificationMethod(),
                entity.getVerificationStatus(),
                entity.getStatus(),
                entity.getDeliveryMethod(),
                entity.getDispenseDate(),
                entity.getDispensedAt(),
                entity.getVerifiedAt(),
                entity.getRetryCount(),
                entity.getAuditLogId(),
                entity.getItems() == null ? List.of() : entity.getItems().stream()
                        .map(ExternalDispensingItemResponse::from)
                        .toList()
        );
    }
}

record ExternalDispensingItemResponse(
        Long dispensingItemId,
        Long medicineId,
        Long prescriptionItemId,
        Integer quantityDispensed
) {
    static ExternalDispensingItemResponse from(DispensingItem item) {
        if (item == null) {
            return null;
        }

        return new ExternalDispensingItemResponse(
                item.getDispensingItemId(),
                item.getMedicine() != null ? item.getMedicine().getMedicineId() : null,
                item.getPrescriptionItem() != null ? item.getPrescriptionItem().getItemId() : null,
                item.getQuantityDispensed()
        );
    }
}
