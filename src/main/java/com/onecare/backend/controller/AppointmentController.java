package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Appointments", description = "Appointment management APIs")
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @Operation(summary = "Create appointment", description = "Create a new appointment booking")
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_CREATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> createAppointment(
            @Valid @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.createAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Appointment created successfully", response));
    }

    @Operation(summary = "List appointments", description = "Return authorized appointment records")
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ_ALL + "') or hasAuthority('"
            + Permission.APPOINTMENT_READ + "') or hasAuthority('" + Permission.APPOINTMENT_READ_OWN + "')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> listAppointments() {
        List<AppointmentResponse> appointments = appointmentService.listAppointments();
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointments retrieved successfully", appointments));
    }

    @Operation(summary = "Update appointment", description = "Reschedule or update an appointment")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> updateAppointment(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.updateAppointment(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointment updated successfully", response));
    }

    @Operation(summary = "Cancel appointment", description = "Cancel an appointment")
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> cancelAppointment(@PathVariable Long id) {
        AppointmentResponse response = appointmentService.cancelAppointment(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointment cancelled successfully", response));
    }

}
