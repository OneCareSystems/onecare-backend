package com.onecare.backend.service;

import com.onecare.backend.dto.request.DayScheduleRequest;
import com.onecare.backend.dto.request.DoctorScheduleRequest;
import com.onecare.backend.dto.request.TimeSlotRequest;
import com.onecare.backend.dto.response.DayScheduleResponse;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.onecare.backend.dto.response.DoctorScheduleResponse;
import com.onecare.backend.dto.response.ScheduleConflictResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.DoctorSchedule;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.TimeSlotConflictException;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.DoctorScheduleRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Doctor schedule configuration (FR009, UC4). Saving a schedule replaces the
 * doctor's previous one and rejects overlapping time slots with 400
 * "Time slot conflict" plus the conflicting ranges (UC4 alt flow).
 */
@Service
public class DoctorScheduleServiceImpl implements DoctorScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DoctorScheduleServiceImpl.class);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final DoctorScheduleRepository doctorScheduleRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final LocalTime defaultStartTime;
    private final LocalTime defaultEndTime;

    public DoctorScheduleServiceImpl(DoctorScheduleRepository doctorScheduleRepository,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository,
            @Value("${clinic.operating-hours.start-time:16:00}")
            String defaultStartTimeStr,
            @Value("${clinic.operating-hours.end-time:21:00}")
            String defaultEndTimeStr) {
        this.doctorScheduleRepository = doctorScheduleRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.defaultStartTime = LocalTime.parse(defaultStartTimeStr);
        this.defaultEndTime = LocalTime.parse(defaultEndTimeStr);
    }

    @Override
    @Transactional
    public DoctorScheduleResponse getSchedule(Long doctorId) {
        User doctor = findDoctor(doctorId);
        assertCanRead(doctor);

        provisionDefaultScheduleIfNeeded(doctor);

        List<DoctorSchedule> rows = doctorScheduleRepository
                .findByDoctorUserIdOrderByStartTimeAsc(doctor.getUserId());
        return toResponse(doctor.getUserId(), rows);
    }

    /**
     * An active doctor without any configured schedule automatically works the
     * default window (4 PM - 9 PM, from clinic.operating-hours.*) every day of
     * the week, so their time is bookable instead of showing as empty. The
     * rows are materialised once; a Super Admin's explicit save later replaces
     * them. Inactive doctors and doctors with an existing schedule are left
     * untouched.
     */
    private void provisionDefaultScheduleIfNeeded(User doctor) {
        if (doctor.getRole() != Role.DOCTOR || !Boolean.TRUE.equals(doctor.getIsActive())) {
            return;
        }
        if (!doctorScheduleRepository.findByDoctorUserIdOrderByStartTimeAsc(doctor.getUserId()).isEmpty()) {
            return;
        }

        for (DayOfWeek day : DayOfWeek.values()) {
            DoctorSchedule row = new DoctorSchedule();
            row.setDoctor(doctor);
            row.setDayOfWeek(day);
            row.setStartTime(defaultStartTime);
            row.setEndTime(defaultEndTime);
            doctorScheduleRepository.save(row);
        }
        log.info(
                "Provisioned default schedule {}-{} (all days) for active doctor {} without a configured schedule",
                defaultStartTime, defaultEndTime, doctor.getUserId());
    }

    @Override
    @Transactional
    public DoctorScheduleResponse saveSchedule(
            Long doctorId,
            DoctorScheduleRequest request
    ) {
        User doctor = findDoctor(doctorId);

        if (request.days() == null || request.days().isEmpty()) {
            throw new BusinessRuleException(
                    "The weekly schedule must include all seven days");
        }

        Map<DayOfWeek, List<TimeSlotRequest>> slotsByDay =
                new EnumMap<>(DayOfWeek.class);

        for (DayScheduleRequest dayRequest : request.days()) {
            if (dayRequest == null || dayRequest.dayOfWeek() == null) {
                throw new BusinessRuleException(
                        "Every schedule entry must specify a day of the week");
            }

            if (slotsByDay.containsKey(dayRequest.dayOfWeek())) {
                throw new BusinessRuleException(
                        "Duplicate schedule entry for " + dayRequest.dayOfWeek());
            }

            if (dayRequest.timeSlots() == null) {
                throw new BusinessRuleException(
                        "Time slots are required for " + dayRequest.dayOfWeek()
                                + "; use an empty list for a day off");
            }

            slotsByDay.put(
                    dayRequest.dayOfWeek(),
                    validateAndSortSlots(dayRequest.timeSlots())
            );
        }

        Set<DayOfWeek> missingDays = EnumSet.allOf(DayOfWeek.class);
        missingDays.removeAll(slotsByDay.keySet());

        if (!missingDays.isEmpty()) {
            throw new BusinessRuleException(
                    "Include every day in the weekly schedule. Missing: "
                            + missingDays);
        }

        // Replace the previous schedule inside the same transaction.
        doctorScheduleRepository.deleteByDoctorUserId(doctor.getUserId());
        doctorScheduleRepository.flush();

        List<DoctorSchedule> saved = new ArrayList<>();

        for (DayOfWeek day : DayOfWeek.values()) {
            for (TimeSlotRequest slot : slotsByDay.get(day)) {
                DoctorSchedule row = new DoctorSchedule();
                row.setDoctor(doctor);
                row.setDayOfWeek(day);
                row.setStartTime(LocalTime.parse(slot.start(), TIME_FORMAT));
                row.setEndTime(LocalTime.parse(slot.end(), TIME_FORMAT));

                saved.add(doctorScheduleRepository.save(row));
            }
        }

        writeAudit(doctor);

        log.info(
                "Saved weekly schedule for doctor {} with {} configured time slot(s)",
                doctor.getUserId(),
                saved.size()
        );

        return toResponse(doctor.getUserId(), saved);
    }
    /**
     * UC4 alt flow: slots are sorted and every adjacent pair checked, which
     * detects any overlap among intervals. Duplicates therefore surface as
     * conflicts too. Invalid ranges (end <= start) are a plain 400.
     */
    private List<TimeSlotRequest> validateAndSortSlots(List<TimeSlotRequest> slots) {
        List<TimeSlotRequest> sorted = slots.stream()
                .sorted(Comparator.comparing(TimeSlotRequest::start))
                .toList();

        for (TimeSlotRequest slot : sorted) {
            LocalTime start = LocalTime.parse(slot.start(), TIME_FORMAT);
            LocalTime end = LocalTime.parse(slot.end(), TIME_FORMAT);
            if (!end.isAfter(start)) {
                throw new BusinessRuleException(
                        "Invalid time slot " + slot.start() + "-" + slot.end() + ": end must be after start");
            }
        }

        List<ScheduleConflictResponse.TimeSlotRange> conflicting = new ArrayList<>();
        for (int i = 1; i < sorted.size(); i++) {
            TimeSlotRequest previous = sorted.get(i - 1);
            TimeSlotRequest current = sorted.get(i);
            // overlap when the next slot starts before the previous one ends
            if (LocalTime.parse(current.start(), TIME_FORMAT)
                    .isBefore(LocalTime.parse(previous.end(), TIME_FORMAT))) {
                conflicting.add(new ScheduleConflictResponse.TimeSlotRange(previous.start(), previous.end()));
                conflicting.add(new ScheduleConflictResponse.TimeSlotRange(current.start(), current.end()));
            }
        }

        if (!conflicting.isEmpty()) {
            throw new TimeSlotConflictException(new ScheduleConflictResponse(conflicting));
        }
        return sorted;
    }

    private User findDoctor(Long doctorId) {
        if (doctorId == null || doctorId <= 0) {
            throw new BusinessRuleException("Doctor ID must be a positive number");
        }
        User user = userRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor not found with id: " + doctorId));
        if (user.getRole() != Role.DOCTOR) {
            throw new BusinessRuleException("User with id " + doctorId + " is not a doctor");
        }
        return user;
    }

    /** Reading a schedule is open to admins and to the doctor themselves. */
    private void assertCanRead(User doctor) {
        if (SecurityUtil.hasPermission(com.onecare.backend.security.Permission.SCHEDULE_MANAGE)) {
            return;
        }
        Long currentUserId = SecurityUtil.getCurrentUserId().orElse(null);
        if (currentUserId == null || !currentUserId.equals(doctor.getUserId())) {
            throw new AccessDeniedException("Doctors can only read their own schedule");
        }
    }

    private void writeAudit(User doctor) {
        SecurityUtil.getCurrentUserId()
                .flatMap(userRepository::findById)
                .ifPresent(actor -> {
                    AuditLog audit = new AuditLog();
                    audit.setUser(actor);
                    audit.setAction("SCHEDULE_UPDATE");
                    audit.setActionType("UPDATE");
                    audit.setTimestamp(java.time.LocalDateTime.now());
                    audit.setEntityType("DoctorSchedule");
                    audit.setEntityId(String.valueOf(doctor.getUserId()));
                    audit.setDescription("Updated schedule for doctor " + doctor.getUserId());
                    auditLogRepository.save(audit);
                });
    }

    private DoctorScheduleResponse toResponse(
            Long doctorId,
            List<DoctorSchedule> rows
    ) {
        Map<DayOfWeek, List<ScheduleConflictResponse.TimeSlotRange>> slotsByDay =
                new EnumMap<>(DayOfWeek.class);

        for (DayOfWeek day : DayOfWeek.values()) {
            slotsByDay.put(day, new ArrayList<>());
        }

        for (DoctorSchedule row : rows) {
            slotsByDay.get(row.getDayOfWeek()).add(
                    new ScheduleConflictResponse.TimeSlotRange(
                            row.getStartTime().format(TIME_FORMAT),
                            row.getEndTime().format(TIME_FORMAT)
                    )
            );
        }

        List<DayScheduleResponse> days = List.of(DayOfWeek.values()).stream()
                .map(day -> new DayScheduleResponse(
                        day,
                        slotsByDay.get(day).stream()
                                .sorted(Comparator.comparing(
                                        ScheduleConflictResponse.TimeSlotRange::start))
                                .toList()
                ))
                .toList();

        return new DoctorScheduleResponse(doctorId, days);
    }
}
