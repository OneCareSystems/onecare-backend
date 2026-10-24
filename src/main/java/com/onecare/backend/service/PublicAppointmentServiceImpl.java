package com.onecare.backend.service;

import com.onecare.backend.dto.AppointmentConfirmation;
import com.onecare.backend.dto.request.PublicAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.PublicDoctorResponse;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Service
@Transactional
public class PublicAppointmentServiceImpl implements PublicAppointmentService {

    private static final Logger log = LoggerFactory.getLogger(PublicAppointmentServiceImpl.class);

    private final PatientService patientService;
    private final AppointmentService appointmentService;
    private final AppointmentConfirmationNotifier confirmationNotifier;
    private final UserRepository userRepository;

    public PublicAppointmentServiceImpl(PatientService patientService,
                                        AppointmentService appointmentService,
                                        AppointmentConfirmationNotifier confirmationNotifier,
                                        UserRepository userRepository) {
        this.patientService = patientService;
        this.appointmentService = appointmentService;
        this.confirmationNotifier = confirmationNotifier;
        this.userRepository = userRepository;
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

    @Override
    @Transactional(readOnly = true)
    public List<PublicDoctorResponse> listDoctors() {
        return userRepository.findByRoleAndIsActiveTrueOrderByUsernameAsc(Role.DOCTOR).stream()
                .map(PublicDoctorResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getAvailableSlots(Long doctorId, LocalDate date) {
        if (doctorId == null || doctorId <= 0) {
            throw new BusinessRuleException("Doctor ID must be a positive number");
        }
        if (date == null) {
            throw new BusinessRuleException("Date is required");
        }

        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
        // Same validation contract as public booking: a non-doctor or inactive
        // id is a 400, so GET slots and POST appointment fail identically.
        if (doctor.getRole() != Role.DOCTOR || !Boolean.TRUE.equals(doctor.getIsActive())) {
            throw new BusinessRuleException(
                    "Specified user with id: " + doctorId + " is not an active doctor");
        }

        return appointmentService.getAvailableSlots(doctorId, date);
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
