package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.RestoreRequest;
import com.onecare.backend.dto.response.BackupResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.BackupService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@Tag(name = "System Backup & Restore", description = "Clinic configuration: data backup & recovery (FR010, UC5)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/system")
public class SystemBackupController {

    private final BackupService backupService;

    public SystemBackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @Operation(summary = "Create a backup",
            description = "Runs a full data export, encrypted with AES-256-GCM (SDS 2.5.3), "
                    + "and stores it with a timestamp. Requires SYSTEM_BACKUP (Super Admin).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Backup created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SYSTEM_BACKUP permission")
    })
    @PostMapping("/backups")
    @PreAuthorize("hasAuthority('" + Permission.SYSTEM_BACKUP + "')")
    public ResponseEntity<ApiResponse<BackupResponse>> createBackup() {
        BackupResponse response = backupService.createBackup();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Backup created successfully", response));
    }

    @Operation(summary = "List backups",
            description = "Returns every stored backup with its timestamp, filename and size. "
                    + "Requires SYSTEM_BACKUP (Super Admin); others get 403 (AC1).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Backups listed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SYSTEM_BACKUP permission")
    })
    @GetMapping("/backups")
    @PreAuthorize("hasAuthority('" + Permission.SYSTEM_BACKUP + "')")
    public ResponseEntity<ApiResponse<List<BackupResponse>>> listBackups() {
        List<BackupResponse> response = backupService.listBackups();
        return ResponseEntity.ok(new ApiResponse<>(true, "Backups retrieved successfully", response));
    }

    @Operation(summary = "Download a backup",
            description = "Streams the AES-256-GCM encrypted backup file as an attachment. "
                    + "Requires SYSTEM_BACKUP (Super Admin); others get 403 (AC1).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Backup downloaded"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SYSTEM_BACKUP permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Backup not found")
    })
    @GetMapping("/backups/{backupId}/download")
    @PreAuthorize("hasAuthority('" + Permission.SYSTEM_BACKUP + "')")
    public ResponseEntity<byte[]> downloadBackup(@PathVariable Long backupId) {
        BackupService.DownloadedBackup backup = backupService.downloadBackup(backupId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + backup.filename() + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(backup.content().length)
                .body(backup.content());
    }

    @Operation(summary = "Restore system data (destructive)",
            description = "Restores from a stored backupId or an uploaded encrypted backup file. "
                    + "Always requires confirm=true (AC4): without it the request is rejected with "
                    + "400 \"Explicit confirmation required\". The restore replaces all exported data "
                    + "and writes its audit entry after completion. Requires SYSTEM_RESTORE (Super Admin).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Restore completed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing confirmation, no source or invalid backup file"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SYSTEM_RESTORE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Backup not found")
    })
    @PostMapping("/restore")
    @PreAuthorize("hasAuthority('" + Permission.SYSTEM_RESTORE + "')")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> restore(
            @RequestParam(required = false) Long backupId,
            @RequestParam(defaultValue = "false") boolean confirm,
            @RequestParam(required = false) MultipartFile file) {
        RestoreRequest request = new RestoreRequest(backupId, confirm);
        Map<String, Integer> restored = backupService.restore(request, file);
        return ResponseEntity.ok(new ApiResponse<>(true, "Restore completed successfully", restored));
    }
}
