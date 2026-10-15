package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.PatientCreateRequest;
import com.onecare.backend.dto.request.PatientUpdateRequest;
import com.onecare.backend.dto.response.PatientHistoryResponse;
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
            description = "Paginated list (use page, size, sort). Optional 'search' filters by partial "
                    + "name (case-insensitive), exact contact number, or exact patient ID, and paging "
                    + "applies to the filtered results too. Blank 'search' returns 200 with an empty list.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllPatients(
            @Parameter(description = "Partial name, exact contact number, or exact patient ID")
            @RequestParam(required = false) String search,
            Pageable pageable) {

        List<PatientResponse> patients = patientService.findAllPatients(search, pageable);
        String message = search != null ? "Search results" : "Patients retrieved successfully";
        return ResponseEntity.ok(new ApiResponse<>(true, message, patients));
    }

    @Operation(
            summary = "Patient clinical history",
            description = "Returns a patient's previous encounters for clinical continuity: "
                    + "past appointments (each with its clinical notes) and past prescriptions. "
                    + "Doctor role only. 'Previous' means dated before today, plus any "
                    + "already COMPLETED/CANCELLED appointment and any no-longer-ISSUED prescription. "
                    + "Returns 404 when the patient does not exist.")
    @GetMapping("/{patientId}/history")
    @PreAuthorize("hasRole('DOCTOR')")
    public ResponseEntity<ApiResponse<PatientHistoryResponse>> getPatientHistory(
            @PathVariable Long patientId) {
        PatientHistoryResponse response = patientService.getPatientHistory(patientId);
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Patient history retrieved successfully", response));
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
