package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreatePrescriptionRequest;
import com.onecare.backend.dto.response.PrescriptionDetailResponse;
import com.onecare.backend.dto.response.PrescriptionResponse;

import java.util.List;

public interface PrescriptionService {

    /**
     * Creates a prescription (status always ISSUED) together with all its
     * items in a single transaction. The whole request is rejected if any
     * medicine is invalid.
     */
    PrescriptionResponse createPrescription(CreatePrescriptionRequest request);

    /** All prescriptions, optionally filtered by status. Never exposes clinical notes. */
    List<PrescriptionResponse> findAllPrescriptions(String status);

    /** Detail view; clinical notes only for callers with PRESCRIPTION_READ_CLINICAL_NOTES. */
    PrescriptionDetailResponse findPrescriptionById(Long id);

    /** ISSUED -> CANCELLED. Dispensed prescriptions cannot be cancelled (400). */
    void cancelPrescription(Long id);
}
