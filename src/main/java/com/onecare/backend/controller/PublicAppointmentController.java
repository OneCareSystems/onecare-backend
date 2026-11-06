package com.onecare.backend.controller;

import com.onecare.backend.dto.request.PublicAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.service.PublicAppointmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public patient self-service booking surface (DDP-33 / FR043 / UC27).
 * No authentication required, but rate limited by the dedicated
 * public-booking tier of ApiRateLimitFilter.
 */
@Tag(name = "Public Booking", description = "Unauthenticated patient self-service appointment booking (FR043/UC27)")
@RestController
@RequestMapping("/api/public/appointments")
public class PublicAppointmentController {

    private final PublicAppointmentService publicAppointmentService;

    public PublicAppointmentController(PublicAppointmentService publicAppointmentService) {
        this.publicAppointmentService = publicAppointmentService;
    }

    @Operation(summary = "Book an appointment (no login required)",
            description = "Patient self-service booking. The patient is identified by name, phone "
                    + "number and date of birth: an existing patient matching name + contact number "
                    + "is reused (no duplicate record), otherwise a minimal new patient record is "
                    + "created. The appointment is created with status SCHEDULED and a confirmation "
                    + "is triggered. If the slot was taken between page-load and submission the "
                    + "response is 409 and includes alternative free slots. Rate limited per IP.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Appointment booked with status SCHEDULED"),
            @ApiResponse(responseCode = "409",
                    description = "Slot already taken — data carries requestedSlot and alternativeSlots"),
            @ApiResponse(responseCode = "429",
                    description = "Public-booking rate limit exceeded for this client IP")})
    @PostMapping
    public ResponseEntity<com.onecare.backend.dto.ApiResponse<AppointmentResponse>> bookAppointment(
            @Valid @RequestBody PublicAppointmentRequest request) {
        AppointmentResponse response = publicAppointmentService.bookAppointment(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new com.onecare.backend.dto.ApiResponse<>(
                        true, "Appointment booked successfully", response));
    }
}
