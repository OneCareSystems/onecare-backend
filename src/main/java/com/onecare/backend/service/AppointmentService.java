package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentClinicalNotesResponse;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.PatientHistoryResponse;

import java.time.LocalDate;
import java.util.List;

public interface AppointmentService {
    AppointmentResponse createAppointment(CreateAppointmentRequest request);

    /**
     * Appointments scoped to the caller's role, optionally filtered by date and patient:
     * - date set: only that day's appointments
     * - from and/or to set: appointments within that inclusive range
     * - search set: only appointments whose patient matches by partial name
     *   (case-insensitive) or exact contact number; blank/absent matches all
     * - no filters: everything
     *
     * @throws com.onecare.backend.exception.BusinessRuleException when date is combined
     *         with from/to, or when from is after to
     */
    List<AppointmentResponse> listAppointments(LocalDate date, LocalDate from, LocalDate to, String search);

    /** Appointments scheduled for today, scoped to the caller's role. */
    List<AppointmentResponse> findTodaysAppointments();

    /** A single appointment WITHOUT clinical notes. */
    AppointmentResponse getAppointmentById(Long appointmentId);

    AppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request);

    AppointmentResponse cancelAppointment(Long appointmentId);

    /**
     * Clinical notes of an appointment — only for callers holding
     * APPOINTMENT_READ_CLINICAL_NOTES (assigned Doctor or any Pharmacist).
     */
    AppointmentClinicalNotesResponse getClinicalNotes(Long appointmentId);

    /**
     * Previous encounters of a patient for clinical continuity:
     * past appointments (with clinical notes) and past prescriptions.
     */
    PatientHistoryResponse getPatientHistory(Long patientId);
}
