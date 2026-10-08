package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.CreateExternalDispensingRequest;
import com.onecare.backend.dto.request.VerifyExternalDispensingRequest;
import com.onecare.backend.dto.response.ExternalDispensingResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.ExternalDispensingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "External Dispensing", description = "External dispensing / OTC handover workflow")
@RestController
@RequestMapping({"/api/external-dispensing", "/api/external-dispense"})
public class ExternalDispensingController {

    private final ExternalDispensingService externalDispensingService;

    public ExternalDispensingController(ExternalDispensingService externalDispensingService) {
        this.externalDispensingService = externalDispensingService;
    }

    @Operation(summary = "Create an external dispensing event")
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
    public ResponseEntity<ApiResponse<?>> createExternalDispensing(
            @Valid @RequestBody CreateExternalDispensingRequest request) {

        ExternalDispensingResponse response = externalDispensingService.createExternalDispensing(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "External dispensing created successfully", response));
    }

    @Operation(summary = "List external dispensing events")
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
    public ResponseEntity<ApiResponse<?>> findAllExternalDispensing(
            @RequestParam(required = false) String status) {

        List<ExternalDispensingResponse> response = externalDispensingService.findAllExternalDispensing(status);
        return ResponseEntity.ok(new ApiResponse<>(true, "External dispensing retrieved successfully", response));
    }

    @Operation(summary = "Read one external dispensing event")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
    public ResponseEntity<ApiResponse<?>> findExternalDispensingById(@PathVariable Long id) {

        ExternalDispensingResponse response = externalDispensingService.findExternalDispensingById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "External dispensing retrieved successfully", response));
    }

    @Operation(summary = "Verify patient identity for an external dispensing event")
    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
    public ResponseEntity<ApiResponse<?>> verifyExternalDispensing(
            @PathVariable Long id,
            @RequestBody VerifyExternalDispensingRequest request) {

        ExternalDispensingResponse response = externalDispensingService.verifyExternalDispensing(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "External dispensing verified successfully", response));
    }

    @Operation(summary = "Complete an external dispensing event")
    @PostMapping("/{id}/dispense")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
    public ResponseEntity<ApiResponse<?>> completeExternalDispensing(@PathVariable Long id) {

        ExternalDispensingResponse response = externalDispensingService.completeExternalDispensing(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "External dispensing completed successfully", response));
    }
}
