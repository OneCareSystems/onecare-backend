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
@RequestMapping("/api/medicines")
public class MedicineController {

    // Endpoints are defined for RBAC and will be implemented with service logic.

    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_CREATE + "')")
    public ResponseEntity<ApiResponse<?>> createMedicine() {
        return null;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findAllMedicines() {
        return null;
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<ApiResponse<?>> findMedicineById(
            @PathVariable Long id
    ) {
        return null;
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_UPDATE + "')")
    public ResponseEntity<ApiResponse<?>> updateMedicine(
            @PathVariable Long id
    ) {
        return null;
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_DELETE + "')")
    public ResponseEntity<ApiResponse<Void>> deleteMedicine(
            @PathVariable Long id
    ) {
        return null;
    }
}
