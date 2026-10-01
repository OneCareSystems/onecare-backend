package com.onecare.backend.service;

import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class QueueServiceImpl implements QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueServiceImpl.class);

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;

    public QueueServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository) {
        this.appointmentRepository = appointmentRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueueResponse> getQueueToday() {
        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();
        LocalDate today = LocalDate.now();

        List<Appointment> appointments = appointmentRepository
                .findByAppointmentDateOrderByTimeSlotAscAppointmentIdAsc(today).stream()
                .filter(appointment -> appointment.getStatus() != null
                        && appointment.getStatus() != AppointmentStatus.CANCELLED)
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
        if (request == null || request.status() == null) {
            throw new InvalidAppointmentStatusException("Status is required");
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        AppointmentStatus requestedStatus = request.status();

        if (appointment.getStatus() == AppointmentStatus.CANCELLED && requestedStatus != AppointmentStatus.CANCELLED) {
            throw new InvalidAppointmentStatusException("Cancelled appointments cannot be changed to another status");
        }

        if (appointment.getStatus() == AppointmentStatus.COMPLETED && requestedStatus != AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentStatusException("Completed appointments cannot be changed to another status");
        }

        appointment.setStatus(requestedStatus);
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Updated appointment {} status to {}", saved.getAppointmentId(), requestedStatus);
        return AppointmentResponse.from(saved);
    }

    private User getCurrentAuthenticatedUser() {
        Long currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new AccessDeniedException("Authentication required"));

        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
    }

    private Patient resolveSelfPatient(User currentUser) {
        return patientRepository.findAll().stream()
                .filter(patient -> patient.getEmail() != null
                        && currentUser.getEmail() != null
                        && patient.getEmail().equalsIgnoreCase(currentUser.getEmail()))
                .findFirst()
                .orElseThrow(() -> new AccessDeniedException("No trusted patient profile found for the current user"));
    }
}
