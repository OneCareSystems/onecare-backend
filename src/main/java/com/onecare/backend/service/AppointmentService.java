package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentClinicalNotesResponse;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.PatientHistoryResponse;

import java.util.List;

public interface AppointmentService {
    AppointmentResponse createAppointment(CreateAppointmentRequest request);

    List<AppointmentResponse> listAppointments();

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
