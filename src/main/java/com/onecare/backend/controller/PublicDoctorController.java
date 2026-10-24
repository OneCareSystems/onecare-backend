package com.onecare.backend.controller;

import com.onecare.backend.dto.ApiResponse;
import com.onecare.backend.dto.response.PublicDoctorResponse;
import com.onecare.backend.service.PublicAppointmentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Public doctor discovery for patient self-service booking (DDP-33 follow-up /
 * FR043 / UC27): the booking form needs the doctor list and the free slots
 * before it can POST /api/public/appointments. No authentication required.
 * The doctor list is exempt from rate limiting (ApiRateLimitFilter
 * RATE_LIMIT_EXEMPT_PATHS) so a patient can never be locked out of the booking
 * page; available-slots keeps the public-booking tier. Exposes minimal doctor
 * PII (id + display name) and computed availability.
 */
@Tag(name = "Public Booking", description = "Unauthenticated doctor discovery for self-service booking (FR043/UC27)")
@RestController
@RequestMapping("/api/public/doctors")
public class PublicDoctorController {

    private final PublicAppointmentService publicAppointmentService;

    public PublicDoctorController(PublicAppointmentService publicAppointmentService) {
        this.publicAppointmentService = publicAppointmentService;
    }

    @Operation(summary = "List bookable doctors (no login required)",
            description = "Active doctors only, ordered by display name. Returns id + display "
                    + "name — no other staff data. Use the doctorId here for the "
                    + "available-slots lookup and the booking request. Not rate limited.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Doctor list (empty when none are active)")})
    @GetMapping
    public ResponseEntity<ApiResponse<List<PublicDoctorResponse>>> listDoctors() {
        List<PublicDoctorResponse> doctors = publicAppointmentService.listDoctors();
        return ResponseEntity.ok(new ApiResponse<>(true, "Doctors retrieved successfully", doctors));
    }

    @Operation(summary = "Get a doctor's free slots (no login required)",
            description = "Free 30-minute slots for the given date: honours the doctor's "
                    + "configured schedule when present, clinic operating hours otherwise. "
                    + "Past dates, booked slots and non-working days yield an empty list. "
                    + "Unknown doctor id is 404; a non-doctor or inactive id is 400 — the "
                    + "same contract as the booking endpoint. Rate limited per IP.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Free slots (empty list when none)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
                    description = "Invalid doctor id/date, or the id is not an active doctor"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Doctor not found"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429",
                    description = "Public-booking rate limit exceeded for this client IP")})
    @GetMapping("/{doctorId}/available-slots")
    public ResponseEntity<ApiResponse<List<String>>> getAvailableSlots(
            @PathVariable Long doctorId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<String> slots = publicAppointmentService.getAvailableSlots(doctorId, date);
        return ResponseEntity.ok(new ApiResponse<>(true, "Available slots retrieved successfully", slots));
    }
}
