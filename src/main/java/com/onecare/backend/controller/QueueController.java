package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.QueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Queue", description = "Daily queue APIs")
@RestController
@RequestMapping("/api/queue")
public class QueueController {

    private final QueueService queueService;

    public QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @Operation(summary = "Get today's queue", description = "Return today's queue based on appointment data")
    @GetMapping("/today")
        @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ + "') or hasAuthority('"
            + Permission.APPOINTMENT_READ_ALL + "')")
    public ResponseEntity<ApiResponse<List<QueueResponse>>> getTodayQueue() {
        List<QueueResponse> queue = queueService.getQueueToday();
        return ResponseEntity.ok(new ApiResponse<>(true, "Queue retrieved successfully", queue));
    }

    @Operation(summary = "Update queue status", description = "Update a queued appointment status")
    @PatchMapping("/{appointmentId}/status")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> updateQueueStatus(
            @PathVariable Long appointmentId,
            @Valid @RequestBody AppointmentStatusRequest request) {
        AppointmentResponse response = queueService.updateQueueStatus(appointmentId, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Queue status updated successfully", response));
    }
}
