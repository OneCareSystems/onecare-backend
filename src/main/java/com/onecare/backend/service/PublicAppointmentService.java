package com.onecare.backend.service;

import com.onecare.backend.dto.request.PublicAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;

/**
 * Unauthenticated patient self-service booking.
 */
public interface PublicAppointmentService {

    /**
     * Books an appointment for a patient identified only by name, phone number
     * and date of birth. An existing patient matching the name + contact number
     * is reused (DDP-19 duplicate semantics); otherwise a minimal patient record
     * is created. On success the appointment is SCHEDULED and a booking
     * confirmation is triggered via {@link AppointmentConfirmationNotifier}.
     *
     * @throws com.onecare.backend.exception.ResourceNotFoundException when the
     *         doctor does not exist
     * @throws com.onecare.backend.exception.SlotConflictException when the slot
     *         was taken between page-load and submission — carries alternative
     *         free slots
     */
    AppointmentResponse bookAppointment(PublicAppointmentRequest request);
}
