package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.CreatePrescriptionRequest;
import com.onecare.backend.dto.request.UpdatePrescriptionRequest;
import com.onecare.backend.dto.response.PrescriptionDetailResponse;
import com.onecare.backend.dto.response.PrescriptionResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.PrescriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Prescriptions", description = "Prescription & prescription item APIs")
@RestController
@RequestMapping("/api/prescriptions")
public class PrescriptionController {

    private final PrescriptionService prescriptionService;

    public PrescriptionController(PrescriptionService prescriptionService) {
        this.prescriptionService = prescriptionService;
    }

    @Operation(
            summary = "Create a prescription",
            description = "Creates a prescription with one or more items in a single transaction. "
                    + "The doctor is always the authenticated user (persisted role must be DOCTOR); "
                    + "the patient must exist and be active; the appointment is required and must "
                    + "exist. Every item needs an itemType: IN_HOUSE items reference a medicineId "
                    + "that must exist, be active (not quarantined) and not expired; EXTERNAL_PURCHASE "
                    + "items carry a free-text medicineName instead (medicineId must be null) and are "
                    + "never stock-checked, dispensed or invoiced. "
                    + "If any item is invalid the entire request is rejected and no rows are written. "
                    + "Status is always set server-side to ISSUED — clients cannot supply a status. "
                    + "Clinical notes are not part of a prescription; they are stored on the "
                    + "appointment. "
                    + "Returns 201 Created; 400 for validation/business-rule failures; 403 without "
                    + "PRESCRIPTION_CREATE (Doctor only); 404 for an unknown patient/appointment.")
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createPrescription(
            @Valid @RequestBody CreatePrescriptionRequest request) {

        PrescriptionResponse response = prescriptionService.createPrescription(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Prescription created successfully", response));
    }

    @Operation(
            summary = "List prescriptions",
            description = "Returns all prescriptions, optionally filtered by status "
                    + "(ISSUED, DISPENSED or CANCELLED; invalid value returns 400). "
                    + "Returns 403 without PRESCRIPTION_READ_ALL.")
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllPrescriptions(
            @Parameter(description = "Optional status filter: ISSUED, DISPENSED or CANCELLED")
            @RequestParam(required = false) String status) {

        List<PrescriptionResponse> prescriptions = prescriptionService.findAllPrescriptions(status);
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Prescriptions retrieved successfully", prescriptions));
    }

    @Operation(
            summary = "List today's prescriptions",
            description = "Returns prescriptions created today excluding CANCELLED prescriptions. "
                    + "ISSUED and DISPENSED prescriptions are included. "
                    + "Returns 403 without PRESCRIPTION_READ_ALL."
    )
    @GetMapping("/today")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findTodaysPrescriptions() {

        List<PrescriptionResponse> prescriptions =
                prescriptionService.findTodaysPrescriptions();

        return ResponseEntity.ok(
                new ApiResponse<>(
                        true,
                        "Today's prescriptions retrieved successfully",
                        prescriptions
                )
        );
    }

    @Operation(
            summary = "Get a prescription by ID",
            description = "Returns the full prescription including its items. "
                    + "Clinical notes are stored on the appointment, not the prescription. "
                    + "Returns 404 when the prescription does not exist.")
    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.PRESCRIPTION_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.PRESCRIPTION_READ + "')")
    public ResponseEntity<ApiResponse<?>> findPrescriptionById(@PathVariable Long id) {

        PrescriptionDetailResponse response = prescriptionService.findPrescriptionById(id);
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Prescription retrieved successfully", response));
    }

    @Operation(
            summary = "Cancel a prescription",
            description = "Transitions an ISSUED prescription to CANCELLED. "
                    + "Dispensed prescriptions cannot be cancelled — attempting to do so returns "
                    + "400 Bad Request. Returns 404 when the prescription does not exist and "
                    + "403 without PRESCRIPTION_CREATE (Doctor only).")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> cancelPrescription(@PathVariable Long id) {

        prescriptionService.cancelPrescription(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Prescription cancelled successfully"));
    }

    @Operation(
            summary = "Update a prescription (DDP-25)",
            description = "Replaces the content of an ISSUED prescription: the full item list "
                    + "(required; old item lines are replaced). The same medicine rules as create "
                    + "apply, and a rejected request changes nothing. Clinical notes are not part "
                    + "of a prescription. Status, doctor, patient, "
                    + "appointment and date are never modified. Only the doctor who issued the "
                    + "prescription may update it (400 otherwise). Returns 200; 400 for "
                    + "validation/business-rule failures or non-ISSUED status; 404 for an unknown "
                    + "prescription; 403 without PRESCRIPTION_UPDATE (Doctor only) — Admin, "
                    + "Pharmacist and Super Admin receive 403.")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updatePrescription(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePrescriptionRequest request) {

        PrescriptionResponse response =  prescriptionService.updatePrescription(id, request);

        return ResponseEntity.ok(
                new ApiResponse<>(
                        true,
                        "Prescription updated successfully",
                        response));
    }

    // Commenting this end point since it is future DDP things remove this comment when works on it
//    @Operation(
//            summary = "Dispense a prescription (DDP-25)",
//            description = "Not implemented yet — reserved for the dispensing flow.")
//    @PutMapping("/{id}/dispense")
//    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
//    public ResponseEntity<ApiResponse<?>> dispensePrescription( @PathVariable Long id ) {
//
//    }
}
