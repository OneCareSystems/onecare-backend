package com.onecare.backend.service;

import com.onecare.backend.dto.request.DoctorScheduleRequest;
import com.onecare.backend.dto.request.TimeSlotRequest;
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

    public DoctorScheduleServiceImpl(DoctorScheduleRepository doctorScheduleRepository,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository) {
        this.doctorScheduleRepository = doctorScheduleRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public DoctorScheduleResponse getSchedule(Long doctorId) {
        User doctor = findDoctor(doctorId);
        assertCanRead(doctor);

        List<DoctorSchedule> rows = doctorScheduleRepository
                .findByDoctorUserIdOrderByStartTimeAsc(doctor.getUserId());
        return toResponse(doctor.getUserId(), rows);
    }

    @Override
    @Transactional
    public DoctorScheduleResponse saveSchedule(Long doctorId, DoctorScheduleRequest request) {
        User doctor = findDoctor(doctorId);

        List<TimeSlotRequest> sortedSlots = validateAndSortSlots(request.timeSlots());

        // Replace semantics: flush the removal of the previous schedule first
        // so the immediately-flushed inserts cannot collide with old rows.
        doctorScheduleRepository.deleteByDoctorUserId(doctor.getUserId());
        doctorScheduleRepository.flush();

        List<DoctorSchedule> saved = new ArrayList<>();
        for (DayOfWeek day : request.workingDays().stream().distinct().sorted().toList()) {
            for (TimeSlotRequest slot : sortedSlots) {
                DoctorSchedule row = new DoctorSchedule();
                row.setDoctor(doctor);
                row.setDayOfWeek(day);
                row.setStartTime(LocalTime.parse(slot.start(), TIME_FORMAT));
                row.setEndTime(LocalTime.parse(slot.end(), TIME_FORMAT));
                saved.add(doctorScheduleRepository.save(row));
            }
        }

        writeAudit(doctor);
        log.info("Saved schedule for doctor {} with {} working day(s) and {} slot(s)",
                doctor.getUserId(), request.workingDays().size(), sortedSlots.size());
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

    private DoctorScheduleResponse toResponse(Long doctorId, List<DoctorSchedule> rows) {
        List<DayOfWeek> workingDays = rows.stream()
                .map(DoctorSchedule::getDayOfWeek)
                .distinct()
                .sorted()
                .toList();
        List<ScheduleConflictResponse.TimeSlotRange> timeSlots = rows.stream()
                .map(row -> new ScheduleConflictResponse.TimeSlotRange(
                        row.getStartTime().format(TIME_FORMAT),
                        row.getEndTime().format(TIME_FORMAT)))
                .distinct()
                .sorted(Comparator.comparing(ScheduleConflictResponse.TimeSlotRange::start))
                .toList();
        return new DoctorScheduleResponse(doctorId, workingDays, timeSlots);
    }
}
