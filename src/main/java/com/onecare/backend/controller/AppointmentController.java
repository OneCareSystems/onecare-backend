package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentClinicalNotesResponse;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.security.Permission;
import com.onecare.backend.service.AppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@Tag(name = "Appointments", description = "Appointment management APIs")
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @Operation(summary = "Create appointment",
            description = "Create a new appointment booking. clinicalNotes is optional and "
                    + "only accepted from doctors (APPOINTMENT_WRITE_CLINICAL_NOTES).")
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_CREATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> createAppointment(
            @Valid @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.createAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Appointment created successfully", response));
    }

    @Operation(summary = "List appointments",
            description = "Return authorized appointment records. Optionally filter by date: "
                    + "?date=YYYY-MM-DD returns one day; ?from=YYYY-MM-DD&to=YYYY-MM-DD returns an "
                    + "inclusive range (each side optional), and by patient with ?search=... "
                    + "(partial case-insensitive name or exact contact number, same as the patient "
                    + "search). Filters combine; with no params every record is returned. "
                    + "Combining date with from/to, or from after to, returns 400.")
    @GetMapping
        @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ_ALL + "') or hasAuthority('"
            + Permission.APPOINTMENT_READ + "')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> listAppointments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search) {
        List<AppointmentResponse> appointments = appointmentService.listAppointments(date, from, to, search);
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointments retrieved successfully", appointments));
    }

    @Operation(summary = "List today's appointments",
            description = "Returns the caller's authorized appointments scheduled for today, "
                    + "ordered by time slot. Admins see all, doctors their own. "
                    + "Clinical notes follow the usual rules (Doctor own appointments only).")
    @GetMapping("/today")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ_ALL + "') or hasAuthority('"
            + Permission.APPOINTMENT_READ + "')")
    public ResponseEntity<ApiResponse<List<AppointmentResponse>>> findTodaysAppointments() {
        List<AppointmentResponse> appointments = appointmentService.findTodaysAppointments();
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Today's appointments retrieved successfully", appointments));
    }

    @Operation(summary = "Get appointment by ID",
            description = "Returns a single appointment WITHOUT clinical notes. "
                    + "Admins see any appointment; doctors only their own. "
                    + "Clinical notes are available via GET /api/appointments/{id}/clinical-notes. "
                    + "Returns 404 when the appointment does not exist.")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ_ALL + "') or hasAuthority('"
            + Permission.APPOINTMENT_READ + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> getAppointmentById(@PathVariable Long id) {
        AppointmentResponse response = appointmentService.getAppointmentById(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointment retrieved successfully", response));
    }

    @Operation(summary = "Update appointment",
            description = "Reschedule or update an appointment. clinicalNotes is optional "
                    + "(null keeps the current notes) and only accepted from doctors "
                    + "(APPOINTMENT_WRITE_CLINICAL_NOTES).")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> updateAppointment(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.updateAppointment(id, request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointment updated successfully", response));
    }

    @Operation(
            summary = "Get appointment clinical notes",
            description = "Returns the clinical notes stored on the appointment. "
                    + "Requires APPOINTMENT_READ_CLINICAL_NOTES (Doctor and Pharmacist); "
                    + "a doctor may only read notes of appointments assigned to them, "
                    + "a pharmacist may read any appointment's notes. "
                    + "Returns 404 for an unknown appointment.")
    @GetMapping("/{id}/clinical-notes")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ_CLINICAL_NOTES + "')")
    public ResponseEntity<ApiResponse<AppointmentClinicalNotesResponse>> getClinicalNotes(@PathVariable Long id) {
        AppointmentClinicalNotesResponse response = appointmentService.getClinicalNotes(id);
        return ResponseEntity.ok(
                new ApiResponse<>(true, "Clinical notes retrieved successfully", response));
    }

    @Operation(summary = "Cancel appointment", description = "Cancel an appointment")
    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_UPDATE + "')")
    public ResponseEntity<ApiResponse<AppointmentResponse>> cancelAppointment(@PathVariable Long id) {
        AppointmentResponse response = appointmentService.cancelAppointment(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Appointment cancelled successfully", response));
    }

    @Operation(summary = "Get available appointment slots", description = "Return available 30-minute appointment slots for a selected doctor and date")
    @GetMapping("/available-slots")
    @PreAuthorize("hasAuthority('" + Permission.APPOINTMENT_READ_ALL + "') or hasAuthority('"
            + Permission.APPOINTMENT_READ + "')")
    public ResponseEntity<ApiResponse<List<String>>> getAvailableSlots(
            @RequestParam Long doctorId,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate date) {
        List<String> slots = appointmentService.getAvailableSlots(doctorId, date);
        return ResponseEntity.ok(new ApiResponse<>(true, "Available slots retrieved successfully", slots));
    }

}
