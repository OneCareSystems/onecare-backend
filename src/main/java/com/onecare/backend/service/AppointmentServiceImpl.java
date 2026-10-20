package com.onecare.backend.service;

import com.onecare.backend.audit.Audited;
import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentClinicalNotesResponse;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.SlotConflictException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.PatientRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.Permission;
import com.onecare.backend.security.SecurityUtil;
import com.onecare.backend.util.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class AppointmentServiceImpl implements AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);
    private static final Duration DEFAULT_SLOT_DURATION = Duration.ofMinutes(30);

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final LocalTime operatingStartTime;
    private final LocalTime operatingEndTime;
    private final Duration slotDuration;

    @Autowired 
    public AppointmentServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            @Value("${clinic.operating-hours.start-time:16:00}") String startTimeStr,
            @Value("${clinic.operating-hours.end-time:21:00}") String endTimeStr,
            @Value("${clinic.operating-hours.slot-duration-minutes:30}") int slotDurationMinutes) {
        this.appointmentRepository = appointmentRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.operatingStartTime = LocalTime.parse(startTimeStr);
        this.operatingEndTime = LocalTime.parse(endTimeStr);
        this.slotDuration = Duration.ofMinutes(slotDurationMinutes);
    }

    // Overload for tests that instantiate directly with 3 repository parameters
    public AppointmentServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository) {
        this(appointmentRepository, patientRepository, userRepository, "16:00", "21:00", 30);
    }

    @Override
    @Audited(action = "APPOINTMENT_CREATE", module = "APPOINTMENT")
    public AppointmentResponse createAppointment(CreateAppointmentRequest request) {
        validateCreateRequest(request);

        Patient patient = resolveTrustedPatient(request.patientId());
        User doctor = userRepository.findById(request.doctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + request.doctorId()));

        Appointment saved = createAppointmentInternal(patient, doctor, request.appointmentDateTime(), request.reason());
        log.info("Created appointment {} for patient {} with doctor {} at {}",
                saved.getAppointmentId(), patient.getPatientId(), doctor.getUserId(), request.appointmentDateTime());
        return mapAppointment(saved, getCurrentAuthenticatedUser());
    }

    @Override
    public AppointmentResponse createAppointmentForPatient(Long patientId, Long doctorId,
            LocalDateTime appointmentDateTime, String reason) {
        Patient patient = patientRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("Patient not found with id: " + patientId));
        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
        if (doctor.getRole() != Role.DOCTOR || !Boolean.TRUE.equals(doctor.getIsActive())) {
            throw new BusinessRuleException(
                    "Specified user with id: " + doctorId + " is not an active doctor");
        }

        Appointment saved = createAppointmentInternal(patient, doctor, appointmentDateTime, reason);
        log.info("Created appointment {} for patient {} with doctor {} at {} (public booking)",
                saved.getAppointmentId(), patient.getPatientId(), doctor.getUserId(), appointmentDateTime);
        // No authenticated caller on this path: unauthenticated-safe mapping, never clinical notes.
        return AppointmentResponse.from(saved);
    }

    /**
     * Shared slot-protected creation behind staff booking (DDP-20) and public
     * self-booking (DDP-33): clinic-grid assertion -> doctor row lock ->
     * availability assertion -> SCHEDULED entity -> save guarded by the unique
     * doctor/date/slot constraint.
     */
    private Appointment createAppointmentInternal(Patient patient, User doctor,
            LocalDateTime requestedStart, String reason) {
        assertWithinOperatingHours(requestedStart);

        lockDoctor(doctor.getUserId());

        LocalDateTime requestedEnd = requestedStart.plus(slotDuration);
        assertDoctorSlotAvailability(doctor.getUserId(), requestedStart, requestedEnd, null);

        Appointment appointment = new Appointment();
        appointment.setPatient(patient);
        appointment.setDoctor(doctor);
        appointment.setAppointmentDate(requestedStart.toLocalDate());
        appointment.setTimeSlot(requestedStart.toLocalTime());
        appointment.setReason(reason);
        appointment.setStatus(AppointmentStatus.SCHEDULED);

        return saveWithSlotProtection(appointment, doctor.getUserId(), requestedStart, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> listAppointments(LocalDate date, LocalDate from, LocalDate to, String search) {
        validateDateFilters(date, from, to);
        User currentUser = getCurrentAuthenticatedUser();
        String query = search != null ? search.trim() : null;

        return findVisibleAppointments(currentUser).stream()
                .filter(appointment -> matchesDateFilter(appointment.getAppointmentDate(), date, from, to))
                .filter(appointment -> matchesPatientSearch(appointment.getPatient(), query))
                .map(appointment -> mapAppointment(appointment, currentUser))
                .toList();
    }

    /**
     * Patient filter, same semantics as the patient search endpoint: partial
     * case-insensitive name match or exact contact-number match in any stored
     * format (canonical, legacy spaced or as typed). A blank query matches
     * everything; an appointment without a linked patient matches nothing.
     */
    private static boolean matchesPatientSearch(Patient patient, String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        if (patient == null) {
            return false;
        }
        if (patient.getFullName() != null
                && patient.getFullName().toLowerCase().contains(query.toLowerCase())) {
            return true;
        }
        return patient.getContactNo() != null
                && PhoneNumberUtil.lookupVariants(query).contains(patient.getContactNo());
    }

    /**
     * 'date' selects one day; 'from'/'to' select an inclusive range (either may be
     * omitted to leave that side open); no params match every date.
     */
    private static boolean matchesDateFilter(LocalDate appointmentDate, LocalDate date, LocalDate from, LocalDate to) {
        if (date != null) {
            return date.equals(appointmentDate);
        }
        if (appointmentDate == null) {
            return false;
        }
        if (from != null && appointmentDate.isBefore(from)) {
            return false;
        }
        return to == null || !appointmentDate.isAfter(to);
    }

    private static void validateDateFilters(LocalDate date, LocalDate from, LocalDate to) {
        if (date != null && (from != null || to != null)) {
            throw new BusinessRuleException("Filter by either 'date' or 'from'/'to', not both");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessRuleException("'from' must not be after 'to'");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AppointmentResponse> findTodaysAppointments() {
        LocalDate today = LocalDate.now();
        User currentUser = getCurrentAuthenticatedUser();

        return findVisibleAppointments(currentUser).stream()
                .filter(appointment -> today.equals(appointment.getAppointmentDate()))
                .map(appointment -> mapAppointment(appointment, currentUser))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentResponse getAppointmentById(Long appointmentId) {

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        assertAppointmentAccess(appointment);

        // clinical notes deliberately excluded — they are only available through
        // GET /api/appointments/{id}/clinical-notes or the patient history endpoint
        return AppointmentResponse.from(appointment);
    }

    /**
     * Appointments the current user is allowed to see:
     * - SUPER_ADMIN / ADMIN: all
     * - DOCTOR: only appointments assigned to them
     * - everyone else: only their own (email-matched) patient profile
     */
    private List<Appointment> findVisibleAppointments(User currentUser) {
    Role currentRole = currentUser.getRole();

    if (currentRole == Role.SUPER_ADMIN || currentRole == Role.ADMIN) {
        return appointmentRepository.findAll();
    }

    if (currentRole == Role.DOCTOR) {
        return appointmentRepository.findAll().stream()
                .filter(appointment -> appointment.getDoctor() != null
                        && appointment.getDoctor().getUserId()
                                .equals(currentUser.getUserId()))
                .toList();
    }

    Patient selfPatient = resolveSelfPatient(currentUser);

    return appointmentRepository.findAll().stream()
            .filter(appointment -> appointment.getPatient() != null
                    && appointment.getPatient().getPatientId()
                            .equals(selfPatient.getPatientId()))
            .toList();
}

    @Override
    @Audited(action = "APPOINTMENT_UPDATE", module = "APPOINTMENT")
    public AppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Appointment not found with id: " + appointmentId));

        assertAppointmentAccess(appointment);

        // Cancelled appointments must not receive clinical notes.
        if (request.clinicalNotes() != null
                && appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new IllegalStateException(
                    "Clinical notes cannot be added to a cancelled appointment");
        }

        // Clinical notes can only be written by doctors.
        if (request.clinicalNotes() != null) {
            assertCanWriteClinicalNotes();
        }

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
            // rescheduling lands on the same clinic grid as booking
            assertWithinOperatingHours(newDateTime);
            assertDoctorSlotAvailability(doctorId, newDateTime, newDateTime.plus(slotDuration), appointmentId);
            appointment.setAppointmentDate(newDateTime.toLocalDate());
            appointment.setTimeSlot(newDateTime.toLocalTime());
        }

        if (request.reason() != null) {
            appointment.setReason(request.reason());
        }

        if (request.clinicalNotes() != null) {
            appointment.setClinicalNotes(request.clinicalNotes());
        }

        Appointment saved = saveWithSlotProtection(appointment, doctorId, newDateTime, appointmentId);
        log.info("Updated appointment {} to doctor {}, patient {}, slot {}",
                saved.getAppointmentId(),
                saved.getDoctor() != null ? saved.getDoctor().getUserId() : null,
                saved.getPatient() != null ? saved.getPatient().getPatientId() : null,
                newDateTime);
        return mapAppointment(saved, getCurrentAuthenticatedUser());
    }

    @Override
    @Audited(action = "APPOINTMENT_CANCEL", module = "APPOINTMENT")
    public AppointmentResponse cancelAppointment(Long appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        assertAppointmentAccess(appointment);

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            return mapAppointment(appointment, getCurrentAuthenticatedUser());
        }

        if (appointment.getStatus() == AppointmentStatus.COMPLETED) {
            throw new InvalidAppointmentStatusException("Cannot cancel a completed appointment");
        }

        if (appointment.getStatus() == AppointmentStatus.NO_SHOW) {
            throw new InvalidAppointmentStatusException("Cannot cancel a no-show appointment");
        }

        appointment.setStatus(AppointmentStatus.CANCELLED);
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Cancelled appointment {}", saved.getAppointmentId());
        return mapAppointment(saved, getCurrentAuthenticatedUser());
    }

    @Override
    @Transactional(readOnly = true)
    public AppointmentClinicalNotesResponse getClinicalNotes(Long appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment not found with id: " + appointmentId));

        User currentUser = getCurrentAuthenticatedUser();
        Role currentRole = currentUser.getRole();

        if (currentRole == Role.DOCTOR) {
            if (appointment.getDoctor() == null
                    || !appointment.getDoctor().getUserId().equals(currentUser.getUserId())) {
                throw new AccessDeniedException(
                        "Doctors can only read clinical notes of their own appointments");
            }
            return AppointmentClinicalNotesResponse.from(appointment);
        }

        if (currentRole == Role.PHARMACIST) {
            return AppointmentClinicalNotesResponse.from(appointment);
        }

        throw new AccessDeniedException("Clinical notes are restricted to doctors and pharmacists");
    }

    /**
     * Clinical notes are written only by doctors (APPOINTMENT_WRITE_CLINICAL_NOTES).
     * Any other caller sending a non-null clinicalNotes value is rejected.
     */
    private void assertCanWriteClinicalNotes() {
        if (!SecurityUtil.hasPermission(Permission.APPOINTMENT_WRITE_CLINICAL_NOTES)) {
            throw new AccessDeniedException("Only doctors can record clinical notes");
        }
    }

    private AppointmentResponse mapAppointment(Appointment appointment, User currentUser) {
        boolean includeClinicalNotes =
                SecurityUtil.hasPermission(Permission.APPOINTMENT_READ_CLINICAL_NOTES)
                        && (currentUser.getRole() != Role.DOCTOR
                                || (appointment.getDoctor() != null
                                        && appointment.getDoctor().getUserId()
                                                .equals(currentUser.getUserId())));
        return AppointmentResponse.from(appointment, includeClinicalNotes);
    }

    private void lockDoctor(Long doctorId) {
        userRepository.findByIdForUpdate(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
    }

    private Appointment saveWithSlotProtection(Appointment appointment, Long doctorId, LocalDateTime requestedStart,
            Long excludeAppointmentId) {
        List<String> alternatives = findAlternativeSlots(doctorId, requestedStart.toLocalDate(), requestedStart,
                requestedStart.plus(slotDuration), excludeAppointmentId);

        try {
            return appointmentRepository.saveAndFlush(appointment);
        } catch (DataIntegrityViolationException exception) {
            throw new SlotConflictException(
                    "Appointment slot is unavailable for doctor " + doctorId + ". Requested slot: " + requestedStart,
                    requestedStart.toString(),
                    alternatives);
        }
    }

    /**
     * Booking and rescheduling must land on the clinic slot grid: at or after
     * operating start, ending at or before operating end, and aligned to the
     * slot duration (e.g. 16:00, 16:30, ... 20:30 with the default 16:00-21:00
     * / 30-minute config). Off-grid or out-of-hours times are rejected with
     * 400 instead of silently creating appointments that the availability
     * display (getAvailableSlots / alternative slots) never shows.
     */
    private void assertWithinOperatingHours(LocalDateTime requestedStart) {
        LocalTime start = requestedStart.toLocalTime();
        boolean withinHours = !start.isBefore(operatingStartTime)
                && !start.plus(slotDuration).isAfter(operatingEndTime);

        long offsetSeconds = Duration.between(operatingStartTime, start).getSeconds();
        boolean alignedToGrid = offsetSeconds >= 0
                && offsetSeconds % slotDuration.toSeconds() == 0
                && start.getNano() == 0;

        if (!withinHours || !alignedToGrid) {
            throw new BusinessRuleException(String.format(
                    "Appointment time must be a clinic slot between %s and %s, aligned to %d-minute "
                            + "intervals (requested: %s)",
                    operatingStartTime, operatingEndTime, slotDuration.toMinutes(), requestedStart));
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

            if (existing.getStatus() == AppointmentStatus.CANCELLED || existing.getStatus() == AppointmentStatus.NO_SHOW) {
                continue;
            }

            LocalDateTime existingStart = LocalDateTime.of(existing.getAppointmentDate(), existing.getTimeSlot());
            LocalDateTime existingEnd = existingStart.plus(slotDuration);
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
        LocalDateTime candidate = LocalDateTime.of(appointmentDate, operatingStartTime);

        while (!candidate.toLocalTime().plus(slotDuration).isAfter(operatingEndTime) && alternatives.size() < 5) {
            LocalDateTime candidateEnd = candidate.plus(slotDuration);
            boolean free = true;
            for (Appointment existing : appointmentRepository.findByDoctor_UserIdAndAppointmentDate(doctorId,
                    appointmentDate)) {
                if (excludeAppointmentId != null && existing.getAppointmentId() != null
                        && existing.getAppointmentId().equals(excludeAppointmentId)) {
                    continue;
                }
                if (existing.getStatus() == AppointmentStatus.CANCELLED || existing.getStatus() == AppointmentStatus.NO_SHOW) {
                    continue;
                }

                LocalDateTime existingStart = LocalDateTime.of(existing.getAppointmentDate(), existing.getTimeSlot());
                LocalDateTime existingEnd = existingStart.plus(slotDuration);
                if (candidate.isBefore(existingEnd) && existingStart.isBefore(candidateEnd)) {
                    free = false;
                    break;
                }
            }

            if (free && !candidate.equals(requestedStart)) {
                alternatives.add(candidate.toString());
            }
            candidate = candidate.plus(slotDuration);
        }

        return alternatives;
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> getAvailableSlots(Long doctorId, LocalDate date) {
        if (doctorId == null || doctorId <= 0) {
            throw new IllegalArgumentException("Doctor ID must be a positive number");
        }
        if (date == null) {
            throw new IllegalArgumentException("Date is required");
        }

        User doctor = userRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
        if (doctor.getRole() != Role.DOCTOR) {
            throw new IllegalArgumentException("Specified user with id: " + doctorId + " is not a doctor");
        }

        if (date.isBefore(LocalDate.now())) {
            return List.of();
        }

        List<Appointment> existingAppointments = appointmentRepository
                .findByDoctor_UserIdAndAppointmentDate(doctorId, date);

        Set<LocalTime> bookedSlots = existingAppointments.stream()
                .filter(a -> a.getStatus() != AppointmentStatus.CANCELLED
                        && a.getStatus() != AppointmentStatus.NO_SHOW)
                .map(Appointment::getTimeSlot)
                .collect(Collectors.toSet());

        List<String> availableSlots = new ArrayList<>();
        LocalTime candidate = operatingStartTime;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");

        while (!candidate.plus(slotDuration).isAfter(operatingEndTime)) {
            boolean isPast = date.isEqual(LocalDate.now())
                    && !LocalDateTime.of(date, candidate).isAfter(LocalDateTime.now());

            if (!isPast && !bookedSlots.contains(candidate)) {
                availableSlots.add(candidate.format(formatter));
            }
            candidate = candidate.plus(slotDuration);
        }

        return availableSlots;
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
            throw new InvalidAppointmentStatusException("Appointment request is required");
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
