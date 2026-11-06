package com.onecare.backend.scheduler;

import com.onecare.backend.service.BackupService;
import com.onecare.backend.service.SystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemBackupSchedulerTest {

    @Mock
    private SystemSettingService systemSettingService;

    @Mock
    private BackupService backupService;

    private SystemBackupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SystemBackupScheduler(systemSettingService, backupService);
    }

    @Test
    void runScheduledBackupIfDue_triggersOnConfiguredDay() {
        when(systemSettingService.getBackupDayOfMonth()).thenReturn(28);

        boolean due = scheduler.runScheduledBackupIfDue(LocalDate.of(2026, 10, 28));

        assertTrue(due);
        verify(backupService).createBackup();
    }

    @Test
    void runScheduledBackupIfDue_skipsWhenTodayIsNotTheConfiguredDay() {
        when(systemSettingService.getBackupDayOfMonth()).thenReturn(28);

        boolean due = scheduler.runScheduledBackupIfDue(LocalDate.of(2026, 10, 27));

        assertFalse(due);
        verify(backupService, never()).createBackup();
    }

    @Test
    void runScheduledBackupIfDue_day31InAprilRunsOnTheLastDay() {
        when(systemSettingService.getBackupDayOfMonth()).thenReturn(31);

        boolean due = scheduler.runScheduledBackupIfDue(LocalDate.of(2026, 4, 30));

        assertTrue(due);
        verify(backupService).createBackup();
    }

    @Test
    void runScheduledBackupIfDue_day31InFebruaryRunsOnTheLastDay() {
        when(systemSettingService.getBackupDayOfMonth()).thenReturn(31);

        // 2026 is not a leap year: February ends on the 28th
        boolean due = scheduler.runScheduledBackupIfDue(LocalDate.of(2026, 2, 28));

        assertTrue(due);
        verify(backupService).createBackup();
    }

    @Test
    void runScheduledBackupIfDue_day31InLeapFebruaryRunsOnThe29th() {
        when(systemSettingService.getBackupDayOfMonth()).thenReturn(31);

        // 2028 is a leap year: February ends on the 29th
        boolean due = scheduler.runScheduledBackupIfDue(LocalDate.of(2028, 2, 29));

        assertTrue(due);
        verify(backupService).createBackup();
    }

    @Test
    void runScheduledBackupIfDue_survivesBackupFailure() {
        when(systemSettingService.getBackupDayOfMonth()).thenReturn(28);
        when(backupService.createBackup()).thenThrow(new RuntimeException("disk full"));

        boolean due = scheduler.runScheduledBackupIfDue(LocalDate.of(2026, 10, 28));

        assertFalse(due); // failure is logged, not propagated
        verify(backupService).createBackup();
    }
}
