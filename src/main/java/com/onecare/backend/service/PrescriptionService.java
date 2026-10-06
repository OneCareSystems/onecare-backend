package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreatePrescriptionRequest;
import com.onecare.backend.dto.request.UpdatePrescriptionRequest;
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

    /** All today prescriptions, optionally filtered by status. Never exposes clinical notes. */
    List<PrescriptionResponse> findTodaysPrescriptions();

    /** Detail view; clinical notes only for callers with PRESCRIPTION_READ_CLINICAL_NOTES. */
    PrescriptionDetailResponse findPrescriptionById(Long id);

    /**
     * Replaces the content of an ISSUED prescription: clinical notes (when provided)
     * and all item lines. Only the doctor who issued it may update it; medicines are
     * validated before anything is mutated, so a rejected request changes nothing.
     */
    PrescriptionResponse updatePrescription(Long id, UpdatePrescriptionRequest request);

    /** ISSUED -> CANCELLED. Dispensed prescriptions cannot be cancelled (400). */
    void cancelPrescription(Long id);
}
