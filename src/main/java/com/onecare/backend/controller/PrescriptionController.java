package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.security.Permission;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/prescriptions")
public class PrescriptionController {

    // Endpoints are defined for RBAC and will be implemented with service logic.

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createPrescription() {
        return null;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllPrescriptions() {
        return null;
    }

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.PRESCRIPTION_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findPrescriptionById( @PathVariable Long id ) {
        return null;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updatePrescription( @PathVariable Long id ) {
        return null;
    }

    @PutMapping("/{id}/dispense")
    @PreAuthorize("hasAuthority('" + Permission.PRESCRIPTION_DISPENSE + "')")
    public ResponseEntity<ApiResponse<?>> dispensePrescription( @PathVariable Long id ) {
        return null;
    }
}