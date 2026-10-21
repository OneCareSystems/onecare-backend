package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.UpdateBackupScheduleRequest;
import com.onecare.backend.dto.request.UpdateConsultationFeeRequest;
import com.onecare.backend.dto.response.BackupScheduleResponse;
import com.onecare.backend.dto.response.ConsultationFeeResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.SystemSettingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System Settings", description = "Clinic configuration: monthly backup schedule & consultation fee (Super Admin)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/system")
public class SystemSettingsController {

    private final SystemSettingService systemSettingService;

    public SystemSettingsController(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    // ------------------------------------------------------------------
    // Monthly backup schedule
    // ------------------------------------------------------------------

    @Operation(summary = "Get backup schedule",
            description = "Returns the configured day of month for the automatic monthly "
                    + "backup (runs at end of day) and the next scheduled run date. "
                    + "Requires SYSTEM_BACKUP (Super Admin).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Schedule retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SYSTEM_BACKUP permission")
    })
    @GetMapping("/backup/schedule")
    @PreAuthorize("hasAuthority('" + Permission.SYSTEM_BACKUP + "')")
    public ResponseEntity<ApiResponse<BackupScheduleResponse>> getBackupSchedule() {
        BackupScheduleResponse response = systemSettingService.getBackupSchedule();
        return ResponseEntity.ok(new ApiResponse<>(true, "Backup schedule retrieved successfully", response));
    }

    @Operation(summary = "Update backup schedule",
            description = "Sets the day of month (1-31) on which the automatic monthly backup "
                    + "runs at end of day. Days beyond a month's length run on that month's "
                    + "last day. Requires SYSTEM_BACKUP (Super Admin).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Schedule updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid day of month"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SYSTEM_BACKUP permission")
    })
    @PutMapping("/backup/schedule")
    @PreAuthorize("hasAuthority('" + Permission.SYSTEM_BACKUP + "')")
    public ResponseEntity<ApiResponse<BackupScheduleResponse>> updateBackupSchedule(
            @Valid @RequestBody UpdateBackupScheduleRequest request) {
        BackupScheduleResponse response = systemSettingService.updateBackupSchedule(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Backup schedule updated successfully", response));
    }

    // ------------------------------------------------------------------
    // Consultation fee
    // ------------------------------------------------------------------

    @Operation(summary = "Get consultation fee",
            description = "Returns the consultation fee currently applied to newly generated "
                    + "invoices. Requires SUPER_ADMIN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fee retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a Super Admin")
    })
    @GetMapping("/settings/consultation-fee")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ConsultationFeeResponse>> getConsultationFee() {
        ConsultationFeeResponse response = systemSettingService.getConsultationFeeSetting();
        return ResponseEntity.ok(new ApiResponse<>(true, "Consultation fee retrieved successfully", response));
    }

    @Operation(summary = "Update consultation fee",
            description = "Sets the consultation fee applied to the system-managed consultation "
                    + "line of newly generated invoices; takes effect immediately and survives "
                    + "restarts. Requires SUPER_ADMIN.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Fee updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid amount"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not a Super Admin")
    })
    @PutMapping("/settings/consultation-fee")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ConsultationFeeResponse>> updateConsultationFee(
            @Valid @RequestBody UpdateConsultationFeeRequest request) {
        ConsultationFeeResponse response = systemSettingService.updateConsultationFee(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Consultation fee updated successfully", response));
    }
}
