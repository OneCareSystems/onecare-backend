package com.onecare.backend.service;

import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface PatientService {
    PatientResponse createPatient(PatientCreateRequest request);
    Page<PatientResponse> findAllPatients(Pageable pageable);
    PatientResponse findPatientById(Long id);
    List<PatientResponse> searchPatients(String search);

    PatientResponse updatePatient(Long id, PatientUpdateRequest request);

    void deactivatePatient(Long id);
}
