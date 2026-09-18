package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.security.Permission;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/patients")
public class PatientController {

    // Endpoints are defined for RBAC and will be implemented with service logic.

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createPatient() {
        return null;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllPatients() {
        return null;
    }

    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAuthority('" + Permission.PATIENT_READ_ALL + "')"
                    + " or hasAuthority('" + Permission.PATIENT_READ + "')"
    )
    public ResponseEntity<ApiResponse<?>> findPatientById(
            @PathVariable Long id
    ) {
        return null;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updatePatient(
            @PathVariable Long id
    ) {
        return null;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.PATIENT_DELETE + "')")
    public ResponseEntity<ApiResponse<?>> deletePatient( @PathVariable Long id ) {
        return null;
    }
}
