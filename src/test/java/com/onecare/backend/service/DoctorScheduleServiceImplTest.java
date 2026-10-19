package com.onecare.backend.service;

import com.onecare.backend.dto.request.DoctorScheduleRequest;
import com.onecare.backend.dto.request.TimeSlotRequest;
import com.onecare.backend.dto.response.DoctorScheduleResponse;
import com.onecare.backend.entity.DoctorSchedule;
import com.onecare.backend.entity.User;
import com.onecare.backend.enums.Role;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.TimeSlotConflictException;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.DoctorScheduleRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DoctorScheduleServiceImplTest {

    @Mock
    private DoctorScheduleRepository doctorScheduleRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private DoctorScheduleServiceImpl doctorScheduleService;

    private User doctor;

    @BeforeEach
    void setUp() {
        doctor = new User();
        doctor.setUserId(1L);
        doctor.setUsername("doctor");
        doctor.setRole(Role.DOCTOR);
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void saveSchedule_overlappingSlots_throwsTimeSlotConflictWithMessage() {
        // AC2: overlapping slots must be rejected with 400 "Time slot conflict"
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));

        DoctorScheduleRequest request = new DoctorScheduleRequest(
                List.of(DayOfWeek.MONDAY),
                List.of(new TimeSlotRequest("09:00", "12:00"),
                        new TimeSlotRequest("11:00", "13:00")));

        TimeSlotConflictException exception = assertThrows(TimeSlotConflictException.class,
                () -> doctorScheduleService.saveSchedule(1L, request));

        assertEquals("Time slot conflict", exception.getMessage());
        assertTrue(exception.getConflictingSlots().conflictingSlots().size() >= 2);
        verify(doctorScheduleRepository, never()).deleteByDoctorUserId(any());
        verify(doctorScheduleRepository, never()).save(any());
    }

    @Test
    void saveSchedule_duplicateSlots_throwsTimeSlotConflict() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));

        DoctorScheduleRequest request = new DoctorScheduleRequest(
                List.of(DayOfWeek.MONDAY),
                List.of(new TimeSlotRequest("09:00", "12:00"),
                        new TimeSlotRequest("09:00", "12:00")));

        assertThrows(TimeSlotConflictException.class,
                () -> doctorScheduleService.saveSchedule(1L, request));
    }

    @Test
    void saveSchedule_valid_replacesAndSavesOneRowPerDayAndSlot() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        when(doctorScheduleRepository.save(any(DoctorSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DoctorScheduleRequest request = new DoctorScheduleRequest(
                List.of(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
                List.of(new TimeSlotRequest("09:00", "12:00"), new TimeSlotRequest("13:00", "17:00")));

        DoctorScheduleResponse response = doctorScheduleService.saveSchedule(1L, request);

        verify(doctorScheduleRepository).deleteByDoctorUserId(1L);
        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(doctorScheduleRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertEquals(2, response.workingDays().size());
        assertEquals(2, response.timeSlots().size());
        assertTrue(captor.getAllValues().stream()
                .allMatch(row -> row.getDoctor().getUserId().equals(1L)));
    }

    @Test
    void saveSchedule_slotEndingBeforeStart_throwsBusinessRule() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));

        DoctorScheduleRequest request = new DoctorScheduleRequest(
                List.of(DayOfWeek.MONDAY),
                List.of(new TimeSlotRequest("12:00", "09:00")));

        assertThrows(BusinessRuleException.class,
                () -> doctorScheduleService.saveSchedule(1L, request));
    }

    @Test
    void saveSchedule_unknownDoctor_throwsNotFound() {
        when(userRepository.findById(9L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> doctorScheduleService.saveSchedule(9L,
                        new DoctorScheduleRequest(List.of(DayOfWeek.MONDAY),
                                List.of(new TimeSlotRequest("09:00", "12:00")))));
    }

    @Test
    void getSchedule_withoutManagePermissionAndNotOwner_throwsAccessDenied() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        // no authentication at all -> cannot be the owner and has no SCHEDULE_MANAGE
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> doctorScheduleService.getSchedule(1L));
    }
}
