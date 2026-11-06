package com.onecare.backend.service;

import com.onecare.backend.audit.Audited;
import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.request.GenerateInvoiceRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.InvoiceRepository;
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
    private final UserRepository userRepository;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepository;

    public QueueServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository, UserRepository userRepository,
            InvoiceService invoiceService,
            InvoiceRepository invoiceRepository) {
        this.appointmentRepository = appointmentRepository;
        this.userRepository = userRepository;
        this.invoiceService = invoiceService;
        this.invoiceRepository = invoiceRepository;
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
                        && appointment.getStatus() != AppointmentStatus.CANCELLED
                        && appointment.getStatus() != AppointmentStatus.NO_SHOW)
                .filter(appointment -> {
                    if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
                        return true;
                    }
                    if (currentRole == Role.DOCTOR) {
                        return appointment.getDoctor() != null
                                && appointment.getDoctor().getUserId().equals(currentUser.getUserId());
                    }
                    // TODO(patient-login): Patient visibility validation cannot be tested until
                    // patient login functionality is implemented. There is no patient role or
                    // patient credential yet, so a real caller can never resolve to a patient
                    // profile through resolveSelfPatient() and this check is unreachable in an
                    // automated test. The lines below are commented out for now and must be
                    // restored (and covered by tests) once patient login is available.
                    //
                    // Patient selfPatient = resolveSelfPatient(currentUser);
                    // return appointment.getPatient() != null
                    //         && appointment.getPatient().getPatientId().equals(selfPatient.getPatientId());
                    
                    return false;
                })
                .sorted(Comparator.comparing(Appointment::getTimeSlot).thenComparing(Appointment::getAppointmentId))
                .toList();

        List<QueueResponse> queue = new ArrayList<>();
        for (int index = 0; index < appointments.size(); index++) {
            Appointment appointment = appointments.get(index);
            queue.add(new QueueResponse(
                    appointment.getAppointmentId(),
                    appointment.getPatient() != null ? appointment.getPatient().getPatientId() : null,
                    appointment.getPatient() != null ? appointment.getPatient().getFullName() : null,
                    appointment.getDoctor() != null ? appointment.getDoctor().getUserId() : null,
                    appointment.getAppointmentDate(),
                    appointment.getTimeSlot(),
                    appointment.getStatus() != null ? appointment.getStatus().name() : null,
                    index + 1));
        }

        return queue;
    }

    @Override
    @Audited(action = "QUEUE_STATUS_UPDATE", module = "QUEUE")
    public AppointmentResponse updateQueueStatus(Long appointmentId, AppointmentStatusRequest request) {
        if (request == null || request.status() == null) {
            throw new InvalidAppointmentStatusException("Status is required");
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        AppointmentStatus currentStatus = appointment.getStatus();
        AppointmentStatus requestedStatus = request.status();

        if (appointment.getStatus() == AppointmentStatus.CANCELLED && requestedStatus != AppointmentStatus.CANCELLED) {
            throw new InvalidAppointmentStatusException("Cancelled appointments cannot be changed to another status");
        }

        if (currentStatus == AppointmentStatus.COMPLETED && requestedStatus != AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentStatusException("Completed appointments cannot be changed to another status");
        }

        if (currentStatus == AppointmentStatus.NO_SHOW && requestedStatus != AppointmentStatus.NO_SHOW) {
            throw new InvalidAppointmentStatusException("No-show appointments cannot be changed to another status");
        }

        // AC1: Appointments that are already ARRIVED or COMPLETED must not be changed to NO_SHOW
        if (requestedStatus == AppointmentStatus.NO_SHOW) {
            if (currentStatus == AppointmentStatus.ARRIVED || currentStatus == AppointmentStatus.COMPLETED) {
                throw new InvalidAppointmentStatusException(
                        "Appointments that are already ARRIVED or COMPLETED cannot be changed to NO_SHOW");
            }
        }

        // AC1: Only the Doctor can mark the Appointment as COMPLETED after finishing the consultation.
        if (requestedStatus == AppointmentStatus.COMPLETED) {
            User currentUser = getCurrentAuthenticatedUser();
            if (currentUser.getRole() != Role.DOCTOR) {
                throw new AccessDeniedException("Only doctors can mark an appointment as COMPLETED");
            }
            if (appointment.getDoctor() != null && !appointment.getDoctor().getUserId().equals(currentUser.getUserId())) {
                throw new AccessDeniedException("Doctors can only complete their own assigned appointments");
            }
        }

        // AC1: Admin check-in: marking as ARRIVED is only valid from SCHEDULED
        if (requestedStatus == AppointmentStatus.ARRIVED && currentStatus != AppointmentStatus.SCHEDULED) {
            throw new InvalidAppointmentStatusException("Only scheduled appointments can be marked as ARRIVED");
        }

        appointment.setStatus(requestedStatus);
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Updated appointment {} status to {}", saved.getAppointmentId(), requestedStatus);

        // AC3: Automatically create invoice when appointment is marked COMPLETED
        if (requestedStatus == AppointmentStatus.COMPLETED) {
            if (invoiceRepository != null && !invoiceRepository.existsByAppointmentAppointmentId(saved.getAppointmentId())) {
                if (invoiceService != null) {
                    try {
                        invoiceService.generateInvoice(new GenerateInvoiceRequest(saved.getAppointmentId(), null));
                        log.info("Automatically generated invoice for completed appointment {}", saved.getAppointmentId());
                    } catch (Exception ex) {
                        log.error("Failed to auto-generate invoice for completed appointment {}: {}",
                                saved.getAppointmentId(), ex.getMessage(), ex);
                        throw ex;
                    }
                }
            }
        }

        return AppointmentResponse.from(saved);
    }

    private User getCurrentAuthenticatedUser() {
        Long currentUserId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() -> new AccessDeniedException("Authentication required"));

        return userRepository.findById(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated user not found"));
    }

    // private Patient resolveSelfPatient(User currentUser) {
    //     return patientRepository.findAll().stream()
    //             .filter(patient -> patient.getEmail() != null
    //                     && currentUser.getEmail() != null
    //                     && patient.getEmail().equalsIgnoreCase(currentUser.getEmail()))
    //             .findFirst()
    //             .orElseThrow(() -> new AccessDeniedException("No trusted patient profile found for the current user"));
    // }
}
