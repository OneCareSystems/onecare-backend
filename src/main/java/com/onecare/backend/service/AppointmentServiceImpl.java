package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.SlotConflictException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class AppointmentServiceImpl implements AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);
    private static final Duration SLOT_DURATION = Duration.ofMinutes(30);

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;

    public AppointmentServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository) {
        this.appointmentRepository = appointmentRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
    }

    @Override
    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        validateCreateRequest(request);

        Patient patient = resolveTrustedPatient(request.patientId());
        User doctor = userRepository.findById(request.doctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.doctorId()));

        lockDoctor(doctor.getUserId());

        LocalDateTime requestedStart = request.appointmentDateTime();
        LocalDateTime requestedEnd = requestedStart.plus(SLOT_DURATION);
        assertDoctorSlotAvailability(doctor.getUserId(), requestedStart, requestedEnd, null);

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(requestedStart.toLocalDate());
        appointment.setTimeSlot(requestedStart.toLocalTime());
        appointment.setReason(request.reason());
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        Appointment saved = saveWithSlotProtection(appointment, doctor.getUserId(), requestedStart, null);
        log.info("Created appointment {} for patient {} with doctor {} at {}",
                saved.getAppointmentId(), patient.getPatientId(), doctor.getUserId(), requestedStart);
        return AppointmentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> listAppointments() {
        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();

        if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
            return appointmentRepository.findAll().stream().map(AppointmentResponse::from).toList();
        }

        if (currentRole == Role.DOCTOR) {
            return appointmentRepository.findAll().stream()
                    .filter(appointment -> appointment.getDoctor() != null
                            && appointment.getDoctor().getUserId().equals(currentUser.getUserId()))
                    .map(AppointmentResponse::from)
                    .toList();
        }

        Patient selfPatient = resolveSelfPatient(currentUser);
        return appointmentRepository.findAll().stream()
                .filter(appointment -> appointment.getPatient() != null
                        && appointment.getPatient().getPatientId().equals(selfPatient.getPatientId()))
                .map(AppointmentResponse::from)
                .toList();
    }

    @Override
    public AppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        assertAppointmentAccess(appointment);

        Long doctorId = request.doctorId() != null ? request.doctorId()
                : (appointment.getDoctor() != null ? appointment.getDoctor().getUserId() : null);
        LocalDateTime newDateTime = request.appointmentDateTime() != null ? request.appointmentDateTime()
                : LocalDateTime.of(appointment.getAppointmentDate(), appointment.getTimeSlot());

        if (doctorId == null) {
            throw new ResourceNotFoundException("Appointment is missing an assigned doctor");
        }

        lockDoctor(doctorId);

        if (request.doctorId() != null) {
            User doctor = userRepository.findById(request.doctorId())
                    .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.doctorId()));
            appointment.setDoctor(doctor);
        }

        if (request.patientId() != null) {
            User currentUser = getCurrentAuthenticatedUser();
            Role currentRole = currentUser.getRole();

            if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
                Patient patient = patientRepository.findById(request.patientId())
                        .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + request.patientId()));
                appointment.setPatient(patient);
            } else if (currentRole == Role.DOCTOR) {
                throw new AccessDeniedException("Doctors are not allowed to reassign patient ownership");
            } else {
                Patient selfPatient = resolveSelfPatient(currentUser);
                if (!request.patientId().equals(selfPatient.getPatientId())) {
                    throw new AccessDeniedException("Patients can only update their own appointment record");
                }
            }
        }

        if (request.appointmentDateTime() != null) {
            assertDoctorSlotAvailability(doctorId, newDateTime, newDateTime.plus(SLOT_DURATION), appointmentId);
            appointment.setAppointmentDate(newDateTime.toLocalDate());
            appointment.setTimeSlot(newDateTime.toLocalTime());
        }

        if (request.reason() != null) {
            appointment.setReason(request.reason());
        }

        Appointment saved = saveWithSlotProtection(appointment, doctorId, newDateTime, appointmentId);
        log.info("Updated appointment {} to doctor {}, patient {}, slot {}",
                saved.getAppointmentId(),
                saved.getDoctor() != null ? saved.getDoctor().getUserId() : null,
                saved.getPatient() != null ? saved.getPatient().getPatientId() : null,
                newDateTime);
        return AppointmentResponse.from(saved);
    }

    @Override
    public AppointmentResponse cancelAppointment(Long appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        assertAppointmentAccess(appointment);

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return AppointmentResponse.from(appointment);
        }

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentStatusException("Cannot cancel a completed appointment");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Cancelled appointment {}", saved.getAppointmentId());
        return AppointmentResponse.from(saved);
    }

    private void lockDoctor(Long doctorId) {
        userRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
    }

    private Appointment saveWithSlotProtection(Appointment appointment, Long doctorId, LocalDateTime requestedStart,
            Long excludeAppointmentId) {
        try {
            return appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException exception) {
            List<String> alternatives = findAlternativeSlots(doctorId, requestedStart.toLocalDate(), requestedStart,
                    requestedStart.plus(SLOT_DURATION), excludeAppointmentId);
            throw new SlotConflictException(
                    "Appointment slot is unavailable for doctor " + doctorId + ". Requested slot: " + requestedStart,
                    requestedStart.toString(),
                    alternatives);
        }
    }

    private void assertDoctorSlotAvailability(Long doctorId, LocalDateTime requestedStart, LocalDateTime requestedEnd,
            Long excludeAppointmentId) {
        for (Appointment existing : appointmentRepository.findByDoctor_UserIdAndAppointmentDate(doctorId,
                requestedStart.toLocalDate())) {
            if (excludeAppointmentId != null && existing.getAppointmentId() != null
                    && existing.getAppointmentId().equals(excludeAppointmentId)) {
                continue;
            }

            if (existing.getStatus() == AppointmentStatus.CANCELLED) {
                continue;
            }

            LocalDateTime existingStart = LocalDateTime.of(existing.getAppointmentDate(), existing.getTimeSlot());
            LocalDateTime existingEnd = existingStart.plus(SLOT_DURATION);
            boolean overlaps = requestedStart.isBefore(existingEnd) && existingStart.isBefore(requestedEnd);
            if (overlaps) {
                throw new SlotConflictException(
                        "Appointment slot is unavailable for doctor " + doctorId + ". Requested slot: "
                                + requestedStart,
                        requestedStart.toString(),
                        findAlternativeSlots(doctorId, requestedStart.toLocalDate(), requestedStart, requestedEnd,
                                excludeAppointmentId));
            }
        }
    }

    private List<String> findAlternativeSlots(Long doctorId, LocalDate appointmentDate, LocalDateTime requestedStart,
            LocalDateTime requestedEnd, Long excludeAppointmentId) {
        List<String> alternatives = new ArrayList<>();
        LocalDateTime candidate = LocalDateTime.of(appointmentDate, LocalTime.of(9, 0));

        while (!candidate.toLocalTime().isAfter(LocalTime.of(17, 30)) && alternatives.size() < 5) {
            LocalDateTime candidateEnd = candidate.plus(SLOT_DURATION);
            boolean free = true;
            for (Appointment existing : appointmentRepository.findByDoctor_UserIdAndAppointmentDate(doctorId,
                    appointmentDate)) {
                if (excludeAppointmentId != null && existing.getAppointmentId() != null
                        && existing.getAppointmentId().equals(excludeAppointmentId)) {
                    continue;
                }
                if (existing.getStatus() == AppointmentStatus.CANCELLED) {
                    continue;
                }

                LocalDateTime existingStart = LocalDateTime.of(existing.getAppointmentDate(), existing.getTimeSlot());
                LocalDateTime existingEnd = existingStart.plus(SLOT_DURATION);
                if (candidate.isBefore(existingEnd) && existingStart.isBefore(candidateEnd)) {
                    free = false;
                    break;
                }
            }

            if (free && !candidate.equals(requestedStart)) {
                alternatives.add(candidate.toString());
            }
            candidate = candidate.plus(SLOT_DURATION);
        }

        return alternatives;
    }

    private User getCurrentAuthenticatedUser() {
        Long currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new AccessDeniedException("Authentication required"));

        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
    }

    private void assertAppointmentAccess(Appointment appointment) {
        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();

        if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
            return;
        }

        if (currentRole == Role.DOCTOR) {
            if (appointment.getDoctor() != null && appointment.getDoctor().getUserId().equals(currentUser.getUserId())) {
                return;
            }
            throw new AccessDeniedException("Doctors can only manage their own appointments");
        }

        Patient selfPatient = resolveSelfPatient(currentUser);
        if (appointment.getPatient() == null
                || !appointment.getPatient().getPatientId().equals(selfPatient.getPatientId())) {
            throw new AccessDeniedException("Patients can only manage their own appointments");
        }
    }

    private Patient resolveTrustedPatient(Long requestedPatientId) {
        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();

        if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
            return patientRepository.findById(requestedPatientId)
                    .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + requestedPatientId));
        }

        if (currentRole == Role.DOCTOR) {
            throw new AccessDeniedException("Doctors are not allowed to create appointments for patients");
        }

        Patient patient = patientRepository.findById(requestedPatientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + requestedPatientId));

        if (patient.getEmail() == null || currentUser.getEmail() == null
                || !patient.getEmail().equalsIgnoreCase(currentUser.getEmail())) {
            throw new AccessDeniedException("Patients can only create appointments for themselves");
        }

        return patient;
    }

    private Patient resolveSelfPatient(User currentUser) {
        return patientRepository.findAll().stream()
                .filter(patient -> patient.getEmail() != null
                        && currentUser.getEmail() != null
                        && patient.getEmail().equalsIgnoreCase(currentUser.getEmail()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("No trusted patient profile found for the current user"));
    }

    private void validateCreateRequest(CreateAppointmentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Appointment request is required");
        }

        if (request.doctorId() == null || request.doctorId() <= 0) {
            throw new IllegalArgumentException("Doctor ID must be a positive number");
        }

        if (request.patientId() == null || request.patientId() <= 0) {
            throw new IllegalArgumentException("Patient ID must be a positive number");
        }

        if (request.appointmentDateTime() == null || !request.appointmentDateTime().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Appointment date/time must be in the future");
        }
    }
}
