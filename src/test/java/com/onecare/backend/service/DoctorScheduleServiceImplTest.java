package com.onecare.backend.service;

import com.onecare.backend.dto.request.DayScheduleRequest;
import com.onecare.backend.dto.request.DoctorScheduleRequest;
import com.onecare.backend.dto.request.TimeSlotRequest;
import com.onecare.backend.dto.response.DoctorScheduleResponse;
import com.onecare.backend.dto.response.ScheduleConflictResponse;
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

    private DoctorScheduleServiceImpl doctorScheduleService;

    private User doctor;

    @BeforeEach
    void setUp() {
        doctor = new User();
        doctor.setUserId(1L);
        doctor.setUsername("doctor");
        doctor.setRole(Role.DOCTOR);

        // default window: 4 PM - 9 PM, matching clinic.operating-hours.*
        doctorScheduleService = new DoctorScheduleServiceImpl(
                doctorScheduleRepository, userRepository, auditLogRepository, "16:00", "21:00");
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void saveSchedule_overlappingSlots_throwsTimeSlotConflictWithMessage() {
        // AC2: overlapping slots must be rejected with 400 "Time slot conflict".
        // MONDAY is validated first, so the conflict fires before the
        // all-seven-days check below can run.
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));

        DoctorScheduleRequest request = new DoctorScheduleRequest(List.of(
                day(DayOfWeek.MONDAY,
                        new TimeSlotRequest("09:00", "12:00"),
                        new TimeSlotRequest("11:00", "13:00"))));

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

        DoctorScheduleRequest request = new DoctorScheduleRequest(List.of(
                day(DayOfWeek.MONDAY,
                        new TimeSlotRequest("09:00", "12:00"),
                        new TimeSlotRequest("09:00", "12:00"))));

        assertThrows(TimeSlotConflictException.class,
                () -> doctorScheduleService.saveSchedule(1L, request));
    }

    @Test
    void saveSchedule_missingDays_throwsBusinessRuleAndSavesNothing() {
        // weekly schedule contract: all seven days are required, an empty
        // timeSlots list marks a day off
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));

        DoctorScheduleRequest request = new DoctorScheduleRequest(List.of(
                day(DayOfWeek.MONDAY, new TimeSlotRequest("09:00", "12:00"))));

        BusinessRuleException exception = assertThrows(BusinessRuleException.class,
                () -> doctorScheduleService.saveSchedule(1L, request));

        assertTrue(exception.getMessage().contains("Missing"));
        verify(doctorScheduleRepository, never()).deleteByDoctorUserId(any());
        verify(doctorScheduleRepository, never()).save(any());
    }

    @Test
    void saveSchedule_valid_replacesAndSavesOneRowPerDayAndSlot() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        when(doctorScheduleRepository.save(any(DoctorSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // full week: MONDAY and FRIDAY carry two slots each, the rest are days off
        DoctorScheduleRequest request = new DoctorScheduleRequest(List.of(
                day(DayOfWeek.MONDAY,
                        new TimeSlotRequest("09:00", "12:00"),
                        new TimeSlotRequest("13:00", "17:00")),
                day(DayOfWeek.TUESDAY),
                day(DayOfWeek.WEDNESDAY),
                day(DayOfWeek.THURSDAY),
                day(DayOfWeek.FRIDAY,
                        new TimeSlotRequest("09:00", "12:00"),
                        new TimeSlotRequest("13:00", "17:00")),
                day(DayOfWeek.SATURDAY),
                day(DayOfWeek.SUNDAY)));

        DoctorScheduleResponse response = doctorScheduleService.saveSchedule(1L, request);

        verify(doctorScheduleRepository).deleteByDoctorUserId(1L);
        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(doctorScheduleRepository, org.mockito.Mockito.times(9)).save(captor.capture());
        // the response always represents the whole week...
        assertEquals(7, response.days().size());
        // ...with slots only on the configured working days
        assertEquals(2, slotsFor(response, DayOfWeek.MONDAY).size());
        assertEquals(2, slotsFor(response, DayOfWeek.FRIDAY).size());
        assertEquals("09:00", slotsFor(response, DayOfWeek.MONDAY).get(0).start());
        assertEquals("12:00", slotsFor(response, DayOfWeek.MONDAY).get(0).end());
        assertTrue(slotsFor(response, DayOfWeek.SUNDAY).isEmpty());
        assertTrue(captor.getAllValues().stream()
                .allMatch(row -> row.getDoctor().getUserId().equals(1L)));
        assertEquals(4, captor.getAllValues().stream().filter(DoctorSchedule::isAvailable).count());
        assertEquals(5, captor.getAllValues().stream().filter(row -> !row.isAvailable()).count());
        assertTrue(captor.getAllValues().stream()
                .filter(row -> !row.isAvailable())
                .allMatch(row -> row.getStartTime() == null && row.getEndTime() == null));
    }

    @Test
    void saveSchedule_allDaysOff_persistsExplicitUnavailableRows() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        when(doctorScheduleRepository.save(any(DoctorSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DoctorScheduleRequest request = new DoctorScheduleRequest(List.of(
                day(DayOfWeek.MONDAY),
                day(DayOfWeek.TUESDAY),
                day(DayOfWeek.WEDNESDAY),
                day(DayOfWeek.THURSDAY),
                day(DayOfWeek.FRIDAY),
                day(DayOfWeek.SATURDAY),
                day(DayOfWeek.SUNDAY)));

        DoctorScheduleResponse response = doctorScheduleService.saveSchedule(1L, request);

        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(doctorScheduleRepository, org.mockito.Mockito.times(7)).save(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(DoctorSchedule::isAvailable));
        assertTrue(captor.getAllValues().stream()
                .allMatch(row -> row.getStartTime() == null && row.getEndTime() == null));
        assertTrue(response.days().stream().allMatch(day -> day.timeSlots().isEmpty()));
    }

    @Test
    void saveSchedule_slotEndingBeforeStart_throwsBusinessRule() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));

        DoctorScheduleRequest request = new DoctorScheduleRequest(List.of(
                day(DayOfWeek.MONDAY, new TimeSlotRequest("12:00", "09:00"))));

        assertThrows(BusinessRuleException.class,
                () -> doctorScheduleService.saveSchedule(1L, request));
    }

    @Test
    void saveSchedule_unknownDoctor_throwsNotFound() {
        when(userRepository.findById(9L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> doctorScheduleService.saveSchedule(9L,
                        new DoctorScheduleRequest(List.of(
                                day(DayOfWeek.MONDAY, new TimeSlotRequest("09:00", "12:00"))))));
    }

    @Test
    void getSchedule_withoutManagePermissionAndNotOwner_throwsAccessDenied() {
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        // no authentication at all -> cannot be the owner and has no SCHEDULE_MANAGE
        org.springframework.security.core.context.SecurityContextHolder.clearContext();

        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> doctorScheduleService.getSchedule(1L));
    }

    // ------------------------------------------------------------------
    // Default schedule provisioning (active doctor, no configured schedule)
    // ------------------------------------------------------------------

    @Test
    void getSchedule_activeDoctorWithoutSchedule_provisionsDefaultDailyWindow() {
        doctor.setIsActive(true);
        authenticateAsOwner();
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        when(doctorScheduleRepository.findByDoctorUserIdOrderByStartTimeAsc(1L)).thenReturn(List.of());
        when(doctorScheduleRepository.save(any(DoctorSchedule.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DoctorScheduleResponse response = doctorScheduleService.getSchedule(1L);

        // one default 16:00-21:00 row per day of the week
        ArgumentCaptor<DoctorSchedule> captor = ArgumentCaptor.forClass(DoctorSchedule.class);
        verify(doctorScheduleRepository, org.mockito.Mockito.times(7)).save(captor.capture());
        assertEquals(7, captor.getAllValues().size());
        assertTrue(captor.getAllValues().stream().allMatch(row ->
                row.getDoctor().getUserId().equals(1L)
                        && row.getStartTime().equals(java.time.LocalTime.of(16, 0))
                        && row.getEndTime().equals(java.time.LocalTime.of(21, 0))));
        assertTrue(captor.getAllValues().stream()
                .map(DoctorSchedule::getDayOfWeek)
                .collect(java.util.stream.Collectors.toSet())
                .containsAll(java.util.EnumSet.allOf(DayOfWeek.class)));
        // the response always represents the whole week
        assertEquals(7, response.days().size());
    }

    @Test
    void getSchedule_inactiveDoctorWithoutSchedule_doesNotProvision() {
        doctor.setIsActive(false);
        authenticateAsOwner();
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        when(doctorScheduleRepository.findByDoctorUserIdOrderByStartTimeAsc(1L)).thenReturn(List.of());

        doctorScheduleService.getSchedule(1L);

        verify(doctorScheduleRepository, never()).save(any(DoctorSchedule.class));
    }

    @Test
    void getSchedule_existingSchedule_isNotOverwritten() {
        doctor.setIsActive(true);
        authenticateAsOwner();
        DoctorSchedule existing = new DoctorSchedule();
        existing.setDoctor(doctor);
        existing.setDayOfWeek(DayOfWeek.MONDAY);
        existing.setStartTime(java.time.LocalTime.of(9, 0));
        existing.setEndTime(java.time.LocalTime.of(12, 0));
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(doctor));
        when(doctorScheduleRepository.findByDoctorUserIdOrderByStartTimeAsc(1L))
                .thenReturn(List.of(existing));

        DoctorScheduleResponse response = doctorScheduleService.getSchedule(1L);

        verify(doctorScheduleRepository, never()).save(any(DoctorSchedule.class));
        assertEquals(7, response.days().size());
    }

    private DayScheduleRequest day(DayOfWeek dayOfWeek, TimeSlotRequest... slots) {
        return new DayScheduleRequest(dayOfWeek, List.of(slots));
    }

    /** Signs in as the doctor themself, who may always read their own schedule. */
    private void authenticateAsOwner() {
        var authorities = List.of(
                new org.springframework.security.core.authority.SimpleGrantedAuthority(
                        "ROLE_" + doctor.getRole().name()));
        var principal = new com.onecare.backend.security.AppUserDetailsService.AppUserDetails(
                doctor.getUserId(), doctor.getUsername(), "hash",
                true, true, true, true, authorities);
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(new org.springframework.security.authentication
                        .UsernamePasswordAuthenticationToken(principal, "hash", authorities));
    }

    private List<ScheduleConflictResponse.TimeSlotRange> slotsFor(
            DoctorScheduleResponse response, DayOfWeek dayOfWeek) {
        return response.days().stream()
                .filter(day -> day.dayOfWeek() == dayOfWeek)
                .findFirst()
                .orElseThrow()
                .timeSlots();
    }
}
