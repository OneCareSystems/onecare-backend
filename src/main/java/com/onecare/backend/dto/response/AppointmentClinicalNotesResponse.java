package com.onecare.backend.dto.response;

import com.onecare.backend.entity.Appointment;

/**
 * Response for GET /api/appointments/{id}/clinical-notes.
 *
 * Returned only to callers holding APPOINTMENT_READ_CLINICAL_NOTES
 * (Doctor for their own appointments, Pharmacist for any appointment).
 */
public record AppointmentClinicalNotesResponse(
        Long appointmentId,
        Long patientId,
        Long doctorId,
        String clinicalNotes) {

    public static AppointmentClinicalNotesResponse from(Appointment appointment) {
        return new AppointmentClinicalNotesResponse(
                appointment.getAppointmentId(),
                appointment.getPatient() != null ? appointment.getPatient().getPatientId() : null,
                appointment.getDoctor() != null ? appointment.getDoctor().getUserId() : null,
                appointment.getClinicalNotes());
    }
}
