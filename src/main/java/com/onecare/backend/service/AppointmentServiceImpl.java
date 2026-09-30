package com.onecare.backend.service;

import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.enums.Status;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.SlotConflictException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.SecurityUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Service
@Transactional
public class AppointmentServiceImpl implements AppointmentService, QueueService {

    private static final Set<String> ALLOWED_STATUS_VALUES = Set.of(
            "Scheduled",
            "Completed",
            "Cancelled",
            "No-show");

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

        LocalDateTime requestedStart = request.appointmentDateTime();
        LocalDateTime requestedEnd = requestedStart.plus(SLOT_DURATION);

        assertDoctorSlotAvailability(doctor.getUserId(), requestedStart, requestedEnd, null);

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(requestedStart.toLocalDate());
        appointment.setTimeSlot(requestedStart.toLocalTime());
        appointment.setReason(request.reason());
        appointment.setStatus(Status.PENDING);

        Appointment saved = appointmentRepository.save(appointment);
        return AppointmentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> listAppointments() {
        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();

        if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
            return appointmentRepository.findAll().stream()
                    .map(AppointmentResponse::from)
                    .toList();
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

        if (request.doctorId() != null) {
            User doctor = userRepository.findById(request.doctorId())
                    .orElseThrow(
                            () -> new ResourceNotFoundException("Doctor not found with id: " + request.doctorId()));
            appointment.setDoctor(doctor);
        }

        if (request.patientId() != null) {
            User currentUser = getCurrentAuthenticatedUser();
            Role currentRole = currentUser.getRole();

            if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
                Patient patient = patientRepository.findById(request.patientId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Patient not found with id: " + request.patientId()));
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

        Appointment saved = appointmentRepository.save(appointment);
        return AppointmentResponse.from(saved);
    }

    @Override
    public AppointmentResponse cancelAppointment(Long appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        assertAppointmentAccess(appointment);

        if (appointment.getStatus() == Status.CANCELLED) {
            return AppointmentResponse.from(appointment);
        }

        if (appointment.getStatus() == Status.DISPENSED) {
            throw new InvalidAppointmentStatusException("Cannot cancel a completed appointment");
        }

        appointment.setStatus(Status.CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);
        return AppointmentResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueResponse> getQueueToday() {
        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();
        LocalDate today = LocalDate.now();

        List<Appointment> appointments = appointmentRepository
                .findByAppointmentDateOrderByTimeSlotAscAppointmentIdAsc(today).stream()
                .filter(appointment -> appointment.getStatus() != null && appointment.getStatus() != Status.CANCELLED)
                .filter(appointment -> {
                    if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
                        return true;
                    }
                    if (currentRole == Role.DOCTOR) {
                        return appointment.getDoctor() != null
                                && appointment.getDoctor().getUserId().equals(currentUser.getUserId());
                    }
                    Patient selfPatient = resolveSelfPatient(currentUser);
                    return appointment.getPatient() != null
                            && appointment.getPatient().getPatientId().equals(selfPatient.getPatientId());
                })
                .sorted(Comparator.comparing(Appointment::getTimeSlot).thenComparing(Appointment::getAppointmentId))
                .toList();

        List<QueueResponse> queue = new ArrayList<>();
        for (int index = 0; index < appointments.size(); index++) {
            Appointment appointment = appointments.get(index);
            queue.add(new QueueResponse(
                    appointment.getAppointmentId(),
                    appointment.getPatient() != null ? appointment.getPatient().getPatientId() : null,
                    appointment.getDoctor() != null ? appointment.getDoctor().getUserId() : null,
                    appointment.getAppointmentDate(),
                    appointment.getTimeSlot(),
                    appointment.getStatus() != null ? appointment.getStatus().name() : null,
                    index + 1));
        }

        return queue;
    }

    @Override
    public AppointmentResponse updateQueueStatus(Long appointmentId, AppointmentStatusRequest request) {
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new InvalidAppointmentStatusException("Status is required");
        }

        String rawStatus = request.status();
        if (!ALLOWED_STATUS_VALUES.contains(rawStatus)) {
            throw new InvalidAppointmentStatusException(
                    "Status must be one of: Scheduled, Completed, Cancelled, No-show");
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        if (appointment.getStatus() == Status.CANCELLED && !"Cancelled".equals(rawStatus)) {
            throw new InvalidAppointmentStatusException("Cancelled appointments cannot be changed to another status");
        }

        if (appointment.getStatus() == Status.DISPENSED && !"Completed".equals(rawStatus)) {
            throw new InvalidAppointmentStatusException("Completed appointments cannot be changed to another status");
        }

        switch (rawStatus) {
            case "Scheduled" -> appointment.setStatus(Status.PENDING);
            case "Completed" -> appointment.setStatus(Status.DISPENSED);
            case "Cancelled" -> appointment.setStatus(Status.CANCELLED);
            case "No-show" -> appointment.setStatus(Status.CANCELLED);
            default -> throw new InvalidAppointmentStatusException(
                    "Status must be one of: Scheduled, Completed, Cancelled, No-show");
        }

        Appointment saved = appointmentRepository.save(appointment);
        return AppointmentResponse.from(saved);
    }

    private void assertDoctorSlotAvailability(Long doctorId, LocalDateTime requestedStart, LocalDateTime requestedEnd,
            Long excludeAppointmentId) {
        List<Appointment> doctorAppointments = appointmentRepository.findByDoctor_UserIdAndAppointmentDate(doctorId,
                requestedStart.toLocalDate());

        List<String> alternatives = findAlternativeSlots(doctorId, requestedStart.toLocalDate(), requestedStart,
                requestedEnd, excludeAppointmentId);

        for (Appointment existing : doctorAppointments) {
            if (excludeAppointmentId != null && existing.getAppointmentId() != null
                    && existing.getAppointmentId().equals(excludeAppointmentId)) {
                continue;
            }

            if (existing.getStatus() == Status.CANCELLED) {
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
                        alternatives);
            }
        }
    }

    private List<String> findAlternativeSlots(Long doctorId, LocalDate appointmentDate, LocalDateTime requestedStart,
            LocalDateTime requestedEnd, Long excludeAppointmentId) {
        List<String> alternatives = new ArrayList<>();
        LocalTime cursor = LocalTime.of(9, 0);
        LocalDateTime candidate = LocalDateTime.of(appointmentDate, cursor);

        while (!candidate.toLocalTime().isAfter(LocalTime.of(17, 30)) && alternatives.size() < 5) {
            LocalDateTime candidateEnd = candidate.plus(SLOT_DURATION);
            boolean free = true;
            for (Appointment existing : appointmentRepository.findByDoctor_UserIdAndAppointmentDate(doctorId,
                    appointmentDate)) {
                if (excludeAppointmentId != null && existing.getAppointmentId() != null
                        && existing.getAppointmentId().equals(excludeAppointmentId)) {
                    continue;
                }
                if (existing.getStatus() == Status.CANCELLED) {
                    continue;
                }
                LocalDateTime existingStart = LocalDateTime.of(existing.getAppointmentDate(), existing.getTimeSlot());
                LocalDateTime existingEnd = existingStart.plus(SLOT_DURATION);
                if (candidate.isBefore(existingEnd) && existingStart.isBefore(candidateEnd)) {
                    free = false;
                    break;
                }
            }
            if (free && candidate.isAfter(requestedStart)) {
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
            if (appointment.getDoctor() != null
                    && appointment.getDoctor().getUserId().equals(currentUser.getUserId())) {
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
                    .orElseThrow(
                            () -> new ResourceNotFoundException("Patient not found with id: " + requestedPatientId));
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
