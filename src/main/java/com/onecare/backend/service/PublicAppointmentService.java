package com.onecare.backend.service;

import com.onecare.backend.dto.request.PublicAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.PublicDoctorResponse;

import java.time.LocalDate;
import java.util.List;

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

    /**
     * Active doctors a patient may book (id + display name, minimal PII).
     * Ordered by display name so the picker is stable across calls.
     */
    List<PublicDoctorResponse> listDoctors();

    /**
     * Free time slots for a doctor on the given date — the same availability
     * the staff-side endpoint computes: configured schedule windows when the
     * doctor has one, clinic operating hours otherwise; past slots, booked
     * slots and non-working days excluded. Empty list for past dates.
     *
     * @throws com.onecare.backend.exception.BusinessRuleException when the id
     *         is not positive or does not refer to an active doctor
     * @throws com.onecare.backend.exception.ResourceNotFoundException when the
     *         doctor does not exist
     */
    List<String> getAvailableSlots(Long doctorId, LocalDate date);
}
