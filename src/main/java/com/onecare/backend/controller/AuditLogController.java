package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.response.AuditLogResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.AuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Read-only audit trail API (FR041, FR042, UC26). Append-only by construction:
 * this controller declares a single GET mapping - there is no PUT, PATCH or
 * DELETE handler for audit logs anywhere in the application.
 */
@Tag(name = "Audit Logs", description = "access to system activity for Super Admin investigation. Records can never be "
        + "edited or deleted. Super Admin only.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/audit-logs")
public class AuditLogController {

    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Operation(
            summary = "Search the audit trail",
            description = "Newest first, paginated: page defaults to 0, size defaults to 20 and is "
                    + "capped at 100. Optional filters: userId (actor), exact actionType "
                    + "(case-insensitive, e.g. LOGIN_FAILED), module (case-insensitive match on "
                    + "the record's entityType, e.g. AUTH, PATIENT, INVOICE) and an inclusive "
                    + "date range (dateFrom/dateTo as yyyy-MM-dd on timestamp). No filters returns "
                    + "the full trail. An empty result is a normal 200 with data: []. "
                    + "Returns 403 without AUDIT_READ/AUDIT_READ_ALL.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Audit log entries retrieved (possibly empty)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing AUDIT_READ permission")
    })
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.AUDIT_READ_ALL + "') "
            + "or hasAuthority('" + Permission.AUDIT_READ + "')")
    public ResponseEntity<ApiResponse<Page<?>>> searchAuditLogs(
            @Parameter(description = "Filter by acting user id")
            @RequestParam(required = false) Long userId,
            @Parameter(description = "Exact action type, case-insensitive (e.g. LOGIN_FAILED)")
            @RequestParam(required = false) String actionType,
            @Parameter(description = "Module filter, case-insensitive (e.g. AUTH, PATIENT, INVOICE)")
            @RequestParam(required = false) String module,
            @Parameter(description = "Inclusive lower bound of timestamp (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @Parameter(description = "Inclusive upper bound of timestamp (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @Parameter(description = "Zero-based page index, default 0")
            @RequestParam(required = false, defaultValue = "0") Integer page,
            @Parameter(description = "Page size, default 20, max 100")
            @RequestParam(required = false, defaultValue = "20") Integer size) {

        Page<AuditLogResponse> auditLogs =
                auditLogService.search(userId, actionType, module, dateFrom, dateTo, page, size);

        return ResponseEntity.ok(
                new ApiResponse<>(true, "Audit logs retrieved successfully", auditLogs));
    }
}
