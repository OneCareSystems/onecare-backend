package com.onecare.backend.controller;

import com.onecare.backend.dto.request.MedicineRequest;
import com.onecare.backend.dto.request.StockUpdateRequest;
import com.onecare.backend.dto.response.MedicineResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.MedicineService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/medicines")
@Tag(
        name = "Medicine Management",
        description = "APIs for managing medicines and inventory"
)
public class MedicineController {

    private final MedicineService medicineService;

    public MedicineController(MedicineService medicineService) {
        this.medicineService = medicineService;
    }

    @Operation(
            summary = "Create medicine",
            description = "Creates a new medicine record."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "Medicine created successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation failed"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            )
    })
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_CREATE + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<MedicineResponse>> createMedicine(
            @Valid @RequestBody MedicineRequest request
    ) {

        MedicineResponse response =
                medicineService.createMedicine(request);

        return new ResponseEntity<>(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Medicine created successfully",
                        response
                ),
                HttpStatus.CREATED
        );
    }

    @Operation(
            summary = "Get all medicines",
            description = "Returns all medicine records."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Medicines retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            )
    })
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<List<MedicineResponse>>> findAllMedicines() {

        List<MedicineResponse> response =
                medicineService.findAllMedicines();

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Medicines retrieved successfully",
                        response
                )
        );
    }

    @Operation(
            summary = "Get medicine by ID",
            description = "Returns a medicine using its ID."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Medicine retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Medicine not found"
            )
    })
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<MedicineResponse>> findMedicineById(
            @PathVariable Long id
    ) {

        MedicineResponse response =
                medicineService.findMedicineById(id);

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Medicine retrieved successfully",
                        response
                )
        );
    }

    @Operation(
            summary = "Update medicine",
            description = "Updates medicine details without modifying stock quantity."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Medicine updated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation failed"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Medicine not found"
            )
    })
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_UPDATE + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<MedicineResponse>> updateMedicine(
            @PathVariable Long id,
            @Valid @RequestBody MedicineRequest request
    ) {

        MedicineResponse response =
                medicineService.updateMedicine(id, request);

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Medicine updated successfully",
                        response
                )
        );
    }

    @Operation(
            summary = "Delete medicine",
            description = "Deletes a medicine using its ID."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Medicine deleted successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Medicine not found"
            )
    })
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_DELETE + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<Void>> deleteMedicine(
            @PathVariable Long id
    ) {

        medicineService.deleteMedicine(id);

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Medicine deleted successfully"
                )
        );
    }

    @Operation(
            summary = "Update medicine stock",
            description = "Updates medicine stock using a positive or negative stock delta."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Stock updated successfully"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Stock cannot become negative"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            ),
            @ApiResponse(
                    responseCode = "404",
                    description = "Medicine not found"
            )
    })
    @PatchMapping("/{id}/stock")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_UPDATE + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<MedicineResponse>> updateStock(
            @PathVariable Long id,
            @Valid @RequestBody StockUpdateRequest request
    ) {

        MedicineResponse response =
                medicineService.updateStock(id, request);

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Stock updated successfully",
                        response
                )
        );
    }

    @Operation(
            summary = "Get low-stock medicines",
            description = "Returns medicines where stock quantity is at or below the reorder level."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Low-stock medicines retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            )
    })
    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<List<MedicineResponse>>> findLowStockMedicines() {

        List<MedicineResponse> response =
                medicineService.findLowStockMedicines();

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Low-stock medicines retrieved successfully",
                        response
                )
        );
    }

    @Operation(
            summary = "Get near-expiry medicines",
            description = "Returns medicines that are within the configured near-expiry period."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Near-expiry medicines retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            )
    })
    @GetMapping("/near-expiry")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<List<MedicineResponse>>> findNearExpiryMedicines() {

        List<MedicineResponse> response =
                medicineService.findNearExpiryMedicines();

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Near-expiry medicines retrieved successfully",
                        response
                )
        );
    }

    @Operation(
            summary = "Get available medicines",
            description = "Returns medicines that are not quarantined and are available for prescribing or dispensing."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Available medicines retrieved successfully"
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Access denied"
            )
    })
    @GetMapping("/available")
    @PreAuthorize("hasAuthority('" + Permission.MEDICINE_READ_ALL + "')")
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<List<MedicineResponse>>> findAvailableMedicines() {

        List<MedicineResponse> response =
                medicineService.findAvailableMedicines();

        return ResponseEntity.ok(
                new com.onecare.backend.dto.ApiResponse<>(
                        true,
                        "Available medicines retrieved successfully",
                        response
                )
        );
    }
}