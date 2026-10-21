package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.DoctorScheduleRequest;
import com.onecare.backend.dto.response.DoctorScheduleResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.DoctorScheduleService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Doctor Schedule", description = "Clinic configuration: doctor working days & time slots (FR009, UC4)")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/doctors/{doctorId}/schedule")
public class DoctorScheduleController {

    private final DoctorScheduleService doctorScheduleService;

    public DoctorScheduleController(DoctorScheduleService doctorScheduleService) {
        this.doctorScheduleService = doctorScheduleService;
    }

    @Operation(summary = "Get a doctor's schedule",
            description = "Returns the configured working days and time slots. "
                    + "Admins may read any doctor's schedule, a doctor only their own. "
                    + "Empty lists when no schedule has been configured.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Schedule retrieved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Not the owning doctor and missing SCHEDULE_READ"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Doctor not found")
    })
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.SCHEDULE_READ + "')")
    public ResponseEntity<ApiResponse<DoctorScheduleResponse>> getSchedule(@PathVariable Long doctorId) {
        DoctorScheduleResponse response = doctorScheduleService.getSchedule(doctorId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Schedule retrieved successfully", response));
    }

    @Operation(summary = "Replace a doctor's schedule",
            description = "Replaces the full weekly schedule (working days + time slots). "
                    + "Overlapping slots are rejected with 400 \"Time slot conflict\" and the "
                    + "conflicting ranges in data.conflictingSlots (UC4 alt flow). "
                    + "Requires SCHEDULE_MANAGE (Super Admin).")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Schedule saved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or time slot conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SCHEDULE_MANAGE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Doctor not found")
    })
    @PutMapping
    @PreAuthorize("hasAuthority('" + Permission.SCHEDULE_MANAGE + "')")
    public ResponseEntity<ApiResponse<DoctorScheduleResponse>> updateSchedule(
            @PathVariable Long doctorId,
            @Valid @RequestBody DoctorScheduleRequest request) {
        DoctorScheduleResponse response = doctorScheduleService.saveSchedule(doctorId, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Schedule saved successfully", response));
    }

    @Operation(summary = "Replace a doctor's schedule (POST alias)",
            description = "Same as PUT — creates or replaces the doctor's schedule. "
                    + "Overlapping slots are rejected with 400 \"Time slot conflict\".")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Schedule saved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation error or time slot conflict"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing SCHEDULE_MANAGE permission"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Doctor not found")
    })
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.SCHEDULE_MANAGE + "')")
    public ResponseEntity<ApiResponse<DoctorScheduleResponse>> createSchedule(
            @PathVariable Long doctorId,
            @Valid @RequestBody DoctorScheduleRequest request) {
        DoctorScheduleResponse response = doctorScheduleService.saveSchedule(doctorId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Schedule saved successfully", response));
    }
}
