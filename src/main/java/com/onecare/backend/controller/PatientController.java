package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.PatientService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createPatient(@Valid @RequestBody PatientCreateRequest request) {
        PatientResponse response = patientService.createPatient(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Patient registered successfully", response));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllPatients(
            @RequestParam(required = false) String search,
            Pageable pageable) {

        if (search != null) {
            List<PatientResponse> results = patientService.searchPatients(search);
            return ResponseEntity.ok(new ApiResponse<>(true, "Search results", results));
        }

        Page<PatientResponse> page = patientService.findAllPatients(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patients retrieved successfully", page));
    }

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.PATIENT_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.PATIENT_READ + "')")
    public ResponseEntity<ApiResponse<?>> findPatientById(@PathVariable Long id) {
        PatientResponse response = patientService.findPatientById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patient retrieved successfully", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updatePatient(@PathVariable Long id,
                                                         @Valid @RequestBody PatientUpdateRequest request) {
        PatientResponse response = patientService.updatePatient(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patient updated successfully", response));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_DELETE + "')")
    public ResponseEntity<ApiResponse<?>> deletePatient(@PathVariable Long id) {
        patientService.deactivatePatient(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patient deactivated successfully"));
    }
}