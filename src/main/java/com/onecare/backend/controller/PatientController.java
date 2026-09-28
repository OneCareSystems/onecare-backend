package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.PatientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Patients", description = "Patient management APIs")
@RestController
@RequestMapping("/api/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @Operation(
            summary = "Register a patient",
            description = "Creates a patient with a system-generated patient ID. Any client-supplied ID is ignored. "
                    + "If first name, last name and contact number match an existing patient, the record is still "
                    + "created and the response contains duplicateSuspected=true.")
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createPatient(@Valid @RequestBody PatientCreateRequest request) {
        PatientResponse response = patientService.createPatient(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Patient registered successfully", response));
    }

    @Operation(
            summary = "List or search patients",
            description = "Without 'search': returns a paginated Page of patients (use page, size, sort). "
                    + "With 'search': returns a plain List matching partial name, exact contact number, "
                    + "or exact patient ID. No match returns 200 with an empty list.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllPatients(
            @Parameter(description = "Partial name, exact contact number, or exact patient ID")
            @RequestParam(required = false) String search,
            Pageable pageable) {

        if (search != null) {
            List<PatientResponse> results = patientService.searchPatients(search);
            return ResponseEntity.ok(new ApiResponse<>(true, "Search results", results));
        }

        List<PatientResponse> patients = patientService.findAllPatients(pageable);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patients retrieved successfully", patients));
    }

    @Operation(summary = "Get a patient by ID")
    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.PATIENT_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.PATIENT_READ + "')")
    public ResponseEntity<ApiResponse<?>> findPatientById(@PathVariable Long id) {
        PatientResponse response = patientService.findPatientById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patient retrieved successfully", response));
    }

    @Operation(
            summary = "Update a patient",
            description = "Partial update: only the fields sent are changed. Roles without PATIENT_UPDATE "
                    + "(for example Pharmacist) receive 403 Forbidden.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updatePatient(@PathVariable Long id,
                                                         @Valid @RequestBody PatientUpdateRequest request) {
        PatientResponse response = patientService.updatePatient(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patient updated successfully", response));
    }

    @Operation(
            summary = "Deactivate a patient",
            description = "Soft delete only: sets isActive=false. The record is never physically removed.")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_DELETE + "')")
    public ResponseEntity<ApiResponse<?>> deletePatient(@PathVariable Long id) {
        patientService.deactivatePatient(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Patient deactivated successfully"));
    }
}