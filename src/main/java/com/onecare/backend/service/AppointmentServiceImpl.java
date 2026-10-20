package com.onecare.backend.service;

import com.onecare.backend.audit.Audited;
import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentClinicalNotesResponse;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.entity.Appointment;
import com.onecare.backend.entity.DoctorSchedule;
import com.onecare.backend.entity.Patient;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.InvalidAppointmentStatusException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.SlotConflictException;
import com.onecare.backend.repository.AppointmentRepository;
import com.onecare.backend.repository.DoctorScheduleRepository;
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

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@Transactional
public class AppointmentServiceImpl implements AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentServiceImpl.class);
    private static final Duration DEFAULT_SLOT_DURATION = Duration.ofMinutes(30);

    private final AppointmentRepository appointmentRepository;
    private final PatientRepository patientRepository;
    private final UserRepository userRepository;
    private final DoctorScheduleRepository doctorScheduleRepository;
    private final LocalTime operatingStartTime;
    private final LocalTime operatingEndTime;
    private final Duration slotDuration;

    @Autowired 
    public AppointmentServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository,
            DoctorScheduleRepository doctorScheduleRepository,
            @Value("${clinic.operating-hours.start-time:16:00}") String startTimeStr,
            @Value("${clinic.operating-hours.end-time:21:00}") String endTimeStr,
            @Value("${clinic.operating-hours.slot-duration-minutes:30}") int slotDurationMinutes) {
        this.appointmentRepository = appointmentRepository;
        this.patientRepository = patientRepository;
        this.userRepository = userRepository;
        this.doctorScheduleRepository = doctorScheduleRepository;
        this.operatingStartTime = LocalTime.parse(startTimeStr);
        this.operatingEndTime = LocalTime.parse(endTimeStr);
        this.slotDuration = Duration.ofMinutes(slotDurationMinutes);
    }

    // Overload for tests that instantiate directly with 3 repository parameters
    public AppointmentServiceImpl(
            AppointmentRepository appointmentRepository,
            PatientRepository patientRepository,
            UserRepository userRepository) {
        this(appointmentRepository, patientRepository, userRepository, null, "16:00", "21:00", 30);
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
        assertWithinOperatingHours(doctor.getUserId(), requestedStart);

        lockDoctor(doctor.getUserId());

        LocalDateTime requestedEnd = requestedStart.plus(slotDuration);
        assertDoctorSlotAvailability(doctor.getUserId(), requestedStart, requestedEnd, null);
        assertWithinWorkingHours(doctor.getUserId(), requestedStart, requestedEnd, null);

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
            // rescheduling lands on the same time grid as booking
            assertWithinOperatingHours(doctorId, newDateTime);
            assertDoctorSlotAvailability(doctorId, newDateTime, newDateTime.plus(slotDuration), appointmentId);
            assertWithinWorkingHours(doctorId, newDateTime, newDateTime.plus(slotDuration), appointmentId);
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
     * Booking and rescheduling must land on the slot grid of the time source
     * that governs the doctor, so an appointment is never silently created
     * that the availability display (getAvailableSlots / alternative slots)
     * never shows.
     *
     * <p>A doctor with a configured schedule (DDP-20 rework) is governed by
     * it: their working windows may sit outside the clinic-wide operating
     * hours (e.g. a 09:00-12:00 schedule when the clinic-hours default starts
     * at 16:00), and the schedule is exactly what availability offers. The
     * clinic gate is therefore skipped for them and the window + grid checks
     * in {@link #assertWithinWorkingHours} apply instead. Doctors without a
     * schedule keep the clinic-wide fallback: at or after operating start,
     * ending at or before operating end, aligned to the slot duration (e.g.
     * 16:00, 16:30, ... 20:30 with the default 16:00-21:00 / 30-minute
     * config). Off-grid or out-of-hours times are rejected with 400.
     */
    private void assertWithinOperatingHours(Long doctorId, LocalDateTime requestedStart) {
        if (doctorScheduleRepository != null && hasAnySchedule(doctorId)) {
            return; // the configured schedule is authoritative (DDP-20 rework)
        }

        LocalTime start = requestedStart.toLocalTime();
        LocalDateTime requestedEnd = requestedStart.plus(slotDuration);
        LocalDateTime operatingEnd = LocalDateTime.of(requestedStart.toLocalDate(), operatingEndTime);
        boolean withinHours = !start.isBefore(operatingStartTime)
                && !requestedEnd.isAfter(operatingEnd);

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
        List<Appointment> appointments = appointmentRepository.findByDoctor_UserIdAndAppointmentDate(
                doctorId, requestedStart.toLocalDate());
        if (hasAppointmentOverlap(appointments, requestedStart, requestedEnd, excludeAppointmentId)) {
            throw new SlotConflictException(
                    "Appointment slot is unavailable for doctor " + doctorId + ". Requested slot: "
                            + requestedStart,
                    requestedStart.toString(),
                    findAlternativeSlots(doctorId, requestedStart.toLocalDate(), requestedStart, requestedEnd,
                            excludeAppointmentId));
        }
    }

    private List<String> findAlternativeSlots(Long doctorId, LocalDate appointmentDate, LocalDateTime requestedStart,
            LocalDateTime requestedEnd, Long excludeAppointmentId) {
        List<String> alternatives = new ArrayList<>();

        // Real doctor schedule (DDP-20 rework): candidates only inside the
        // doctor's working windows; a non-working day offers no alternatives.
        List<DoctorSchedule> windows = workingWindowsForDay(doctorId, appointmentDate.getDayOfWeek());
        if (!windows.isEmpty() && hasAnySchedule(doctorId)) {
            for (DoctorSchedule window : windows) {
                LocalDateTime candidate = LocalDateTime.of(appointmentDate, window.getStartTime());
                LocalDateTime windowEnd = LocalDateTime.of(appointmentDate, window.getEndTime());
                while (!candidate.plus(slotDuration).isAfter(windowEnd)
                        && alternatives.size() < 5) {
                    LocalDateTime candidateEnd = candidate.plus(slotDuration);
                    if (freeOfAppointments(doctorId, appointmentDate, candidate, candidateEnd, excludeAppointmentId)
                            && !candidate.equals(requestedStart)) {
                        alternatives.add(candidate.toString());
                    }
                    candidate = candidate.plus(slotDuration);
                }
                if (alternatives.size() >= 5) {
                    break;
                }
            }
            return alternatives;
        }

        LocalDateTime candidate = LocalDateTime.of(appointmentDate, operatingStartTime);
        LocalDateTime operatingEnd = LocalDateTime.of(appointmentDate, operatingEndTime);

        while (!candidate.plus(slotDuration).isAfter(operatingEnd) && alternatives.size() < 5) {
            LocalDateTime candidateEnd = candidate.plus(slotDuration);
            if (freeOfAppointments(doctorId, appointmentDate, candidate, candidateEnd, excludeAppointmentId)
                    && !candidate.equals(requestedStart)) {
                alternatives.add(candidate.toString());
            }
            candidate = candidate.plus(slotDuration);
        }

        return alternatives;
    }

    private boolean freeOfAppointments(Long doctorId, LocalDate appointmentDate, LocalDateTime candidateStart,
            LocalDateTime candidateEnd, Long excludeAppointmentId) {
        List<Appointment> appointments = appointmentRepository.findByDoctor_UserIdAndAppointmentDate(
                doctorId, appointmentDate);
        return !hasAppointmentOverlap(appointments, candidateStart, candidateEnd, excludeAppointmentId);
    }

    private boolean hasAppointmentOverlap(List<Appointment> appointments, LocalDateTime candidateStart,
            LocalDateTime candidateEnd, Long excludeAppointmentId) {
        return appointments.stream()
                .filter(appointment -> excludeAppointmentId == null
                        || appointment.getAppointmentId() == null
                        || !appointment.getAppointmentId().equals(excludeAppointmentId))
                .filter(appointment -> appointment.getStatus() != AppointmentStatus.CANCELLED
                        && appointment.getStatus() != AppointmentStatus.NO_SHOW)
                .anyMatch(appointment -> {
                    LocalDateTime existingStart =
                            LocalDateTime.of(appointment.getAppointmentDate(), appointment.getTimeSlot());
                    LocalDateTime existingEnd = existingStart.plus(slotDuration);
                    return candidateStart.isBefore(existingEnd) && existingStart.isBefore(candidateEnd);
                });
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

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HH:mm");
        List<String> availableSlots = new ArrayList<>();

        // Real doctor schedule (DDP-20 rework): a doctor with a configured
        // schedule is only bookable on working days, inside their windows.
        List<DoctorSchedule> windows = workingWindowsForDay(doctorId, date.getDayOfWeek());
        if (hasAnySchedule(doctorId)) {
            if (windows.isEmpty()) {
                return List.of(); // non-working day
            }
            for (DoctorSchedule window : windows) {
                LocalDateTime candidate = LocalDateTime.of(date, window.getStartTime());
                LocalDateTime windowEnd = LocalDateTime.of(date, window.getEndTime());
                while (!candidate.plus(slotDuration).isAfter(windowEnd)) {
                    LocalTime candidateTime = candidate.toLocalTime();
                    boolean isPast = date.isEqual(LocalDate.now())
                            && !candidate.isAfter(LocalDateTime.now());
                    if (!isPast && !hasAppointmentOverlap(
                            existingAppointments, candidate, candidate.plus(slotDuration), null)) {
                        availableSlots.add(candidateTime.format(formatter));
                    }
                    candidate = candidate.plus(slotDuration);
                }
            }
            return availableSlots;
        }

        // Fallback: clinic-wide operating hours until a schedule is configured
        LocalDateTime candidate = LocalDateTime.of(date, operatingStartTime);
        LocalDateTime operatingEnd = LocalDateTime.of(date, operatingEndTime);

        while (!candidate.plus(slotDuration).isAfter(operatingEnd)) {
            LocalTime candidateTime = candidate.toLocalTime();
            boolean isPast = date.isEqual(LocalDate.now())
                    && !candidate.isAfter(LocalDateTime.now());

            if (!isPast && !hasAppointmentOverlap(
                    existingAppointments, candidate, candidate.plus(slotDuration), null)) {
                availableSlots.add(candidateTime.format(formatter));
            }
            candidate = candidate.plus(slotDuration);
        }

        return availableSlots;
    }

    /** True when the doctor has any configured schedule rows (DDP-20 rework). */
    private boolean hasAnySchedule(Long doctorId) {
        return doctorScheduleRepository != null
                && !doctorScheduleRepository.findByDoctorUserIdOrderByStartTimeAsc(doctorId).isEmpty();
    }

    private List<DoctorSchedule> workingWindowsForDay(Long doctorId, DayOfWeek dayOfWeek) {
        if (doctorScheduleRepository == null) {
            return List.of();
        }
        return doctorScheduleRepository.findByDoctorUserIdAndDayOfWeekOrderByStartTimeAsc(doctorId, dayOfWeek)
                .stream()
                .filter(DoctorSchedule::isAvailable)
                .toList();
    }

    /**
     * Booking must use real availability (FR009): when the doctor has a
     * configured schedule, the requested slot must fall inside a working
     * window of that day AND on that window's slot grid; a non-working day
     * is rejected outright. Doctors without a schedule keep the clinic-hours
     * fallback (enforced by {@link #assertWithinOperatingHours}).
     */
    private void assertWithinWorkingHours(Long doctorId, LocalDateTime requestedStart, LocalDateTime requestedEnd,
            Long excludeAppointmentId) {
        if (doctorScheduleRepository == null || !hasAnySchedule(doctorId)) {
            return;
        }
        List<DoctorSchedule> windows = workingWindowsForDay(doctorId, requestedStart.getDayOfWeek());
        if (windows.isEmpty()) {
            throw new SlotConflictException(
                    "Doctor " + doctorId + " does not work on " + requestedStart.getDayOfWeek()
                            + ". Requested slot: " + requestedStart,
                    requestedStart.toString(),
                    List.of());
        }
        boolean crossesMidnight = !requestedEnd.toLocalDate().equals(requestedStart.toLocalDate());
        boolean within = !crossesMidnight && windows.stream().anyMatch(window ->
                !requestedStart.toLocalTime().isBefore(window.getStartTime())
                        && !requestedEnd.toLocalTime().isAfter(window.getEndTime()));
        if (!within) {
            throw new SlotConflictException(
                    "Requested slot is outside the working hours of doctor " + doctorId
                            + ". Requested slot: " + requestedStart,
                    requestedStart.toString(),
                    findAlternativeSlots(doctorId, requestedStart.toLocalDate(), requestedStart, requestedEnd,
                            excludeAppointmentId));
        }

        // On-grid inside the covering window: availability and alternatives are
        // generated at window-start + n x slotDuration, so an off-grid time
        // (e.g. 09:15 in a 09:00-12:00 window) is never offered and must not
        // be bookable either — rejected with 400 like the clinic-grid gate.
        boolean onGrid = windows.stream().anyMatch(window -> {
            boolean covers = !requestedStart.toLocalTime().isBefore(window.getStartTime())
                    && !requestedEnd.toLocalTime().isAfter(window.getEndTime());
            if (!covers) {
                return false;
            }
            long offsetSeconds =
                    Duration.between(window.getStartTime(), requestedStart.toLocalTime()).getSeconds();
            return offsetSeconds >= 0
                    && offsetSeconds % slotDuration.toSeconds() == 0
                    && requestedStart.getNano() == 0;
        });
        if (!onGrid) {
            throw new BusinessRuleException(String.format(
                    "Appointment time must be aligned to %d-minute slots inside the doctor's working window "
                            + "(requested: %s)",
                    slotDuration.toMinutes(), requestedStart));
        }
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
