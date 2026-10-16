package com.onecare.backend.service;

import com.onecare.backend.dto.AppointmentConfirmation;
import com.onecare.backend.dto.request.PublicAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.entity.Patient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PublicAppointmentServiceImpl implements PublicAppointmentService {

    private static final Logger log = LoggerFactory.getLogger(PublicAppointmentServiceImpl.class);

    private final PatientService patientService;
    private final AppointmentService appointmentService;
    private final AppointmentConfirmationNotifier confirmationNotifier;

    public PublicAppointmentServiceImpl(PatientService patientService,
                                        AppointmentService appointmentService,
                                        AppointmentConfirmationNotifier confirmationNotifier) {
        this.patientService = patientService;
        this.appointmentService = appointmentService;
        this.confirmationNotifier = confirmationNotifier;
    }

    @Override
    public AppointmentResponse bookAppointment(PublicAppointmentRequest request) {
        Patient patient = patientService.findOrCreateForPublicBooking(
                request.fullName().trim(), request.contactNo(), request.dateOfBirth(), request.gender());

        AppointmentResponse appointment = appointmentService.createAppointmentForPatient(
                patient.getPatientId(), request.doctorId(), request.appointmentDateTime(), null);

        log.info("Public booking created | appointmentId={} | patientId={} | doctorId={} | slot={}",
                appointment.appointmentId(), patient.getPatientId(), request.doctorId(),
                request.appointmentDateTime());

        sendConfirmationQuietly(patient, appointment);
        return appointment;
    }

    /**
     * The appointment is already persisted at this point — a notification
     * failure (external service down) must never turn a successful booking
     * into an error for the patient.
     */
    private void sendConfirmationQuietly(Patient patient, AppointmentResponse appointment) {
        try {
            confirmationNotifier.sendBookingConfirmation(new AppointmentConfirmation(
                    appointment.appointmentId(),
                    patient.getFullName(),
                    patient.getContactNo(),
                    patient.getEmail(),
                    appointment.appointmentDate(),
                    appointment.timeSlot()));
        } catch (RuntimeException exception) {
            log.warn("Appointment confirmation could not be dispatched | appointmentId={}",
                    appointment.appointmentId(), exception);
        }
    }
}
