package com.onecare.backend.service;

import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.dto.response.PatientHistoryResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PatientService {
    PatientResponse createPatient(PatientCreateRequest request);

    /**
     * Paginated patient list. search null returns every patient; a non-blank
     * search filters by partial name (case-insensitive), exact contact number
     * or exact patient ID before paging; a blank search returns an empty list.
     */
    List<PatientResponse> findAllPatients(String search, Pageable pageable);
    PatientResponse findPatientById(Long id);

    List<PatientResponse> searchPatients(String search);

    PatientHistoryResponse getPatientHistory(Long patientId);

    PatientResponse updatePatient(Long id, PatientUpdateRequest request);

    void deactivatePatient(Long id);
}
