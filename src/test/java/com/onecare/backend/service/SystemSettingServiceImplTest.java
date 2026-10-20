package com.onecare.backend.service;

import com.onecare.backend.dto.request.UpdateBackupScheduleRequest;
import com.onecare.backend.dto.request.UpdateConsultationFeeRequest;
import com.onecare.backend.dto.response.BackupScheduleResponse;
import com.onecare.backend.dto.response.ConsultationFeeResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.SystemSetting;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.SystemSettingRepository;
import com.onecare.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemSettingServiceImplTest {

    @Mock
    private SystemSettingRepository systemSettingRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private UserRepository userRepository;

    private SystemSettingServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SystemSettingServiceImpl(systemSettingRepository, auditLogRepository,
                userRepository, new BigDecimal("500.00"), "23:59");
    }

    // ------------------------------------------------------------------
    // Backup schedule
    // ------------------------------------------------------------------

    @Test
    void getBackupDayOfMonth_fallsBackToDefaultWhenNoRowExists() {
        when(systemSettingRepository.findById(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH))
                .thenReturn(Optional.empty());

        assertThat(service.getBackupDayOfMonth()).isEqualTo(28);
    }

    @Test
    void getBackupDayOfMonth_fallsBackToDefaultWhenValueIsInvalid() {
        when(systemSettingRepository.findById(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH))
                .thenReturn(Optional.of(setting("not-a-number")));

        assertThat(service.getBackupDayOfMonth()).isEqualTo(28);
    }

    @Test
    void getBackupDayOfMonth_fallsBackToDefaultWhenValueIsOutOfRange() {
        when(systemSettingRepository.findById(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH))
                .thenReturn(Optional.of(setting("42")));

        assertThat(service.getBackupDayOfMonth()).isEqualTo(28);
    }

    @Test
    void getBackupDayOfMonth_returnsStoredValue() {
        when(systemSettingRepository.findById(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH))
                .thenReturn(Optional.of(setting("5")));

        assertThat(service.getBackupDayOfMonth()).isEqualTo(5);
    }

    @Test
    void getBackupSchedule_returnsDayExecutionTimeAndNextRunDate() {
        when(systemSettingRepository.findById(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH))
                .thenReturn(Optional.of(setting("15")));

        BackupScheduleResponse response = service.getBackupSchedule();

        assertThat(response.dayOfMonth()).isEqualTo(15);
        assertThat(response.executionTime()).isEqualTo("23:59");
        assertThat(response.nextRunDate()).isNotNull();
        assertThat(response.nextRunDate().getDayOfMonth()).isEqualTo(15);
    }

    @Test
    void updateBackupSchedule_persistsNewDayAndWritesAuditLog() {
        when(systemSettingRepository.findById(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH))
                .thenReturn(Optional.empty());

        BackupScheduleResponse response =
                service.updateBackupSchedule(new UpdateBackupScheduleRequest(10));

        assertThat(response.dayOfMonth()).isEqualTo(10);

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository).save(saved.capture());
        assertThat(saved.getValue().getSettingKey())
                .isEqualTo(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH);
        assertThat(saved.getValue().getSettingValue()).isEqualTo("10");

        // no authenticated user in this unit test -> audit is skipped, not failed
        verify(auditLogRepository, never()).save(any(AuditLog.class));
    }

    // ------------------------------------------------------------------
    // Consultation fee
    // ------------------------------------------------------------------

    @Test
    void getConsultationFee_fallsBackToPropertyDefaultWhenNoRowExists() {
        when(systemSettingRepository.findById(SystemSetting.KEY_CONSULTATION_FEE))
                .thenReturn(Optional.empty());

        assertThat(service.getConsultationFee()).isEqualByComparingTo("500.00");
    }

    @Test
    void getConsultationFee_fallsBackToPropertyDefaultWhenValueIsInvalid() {
        when(systemSettingRepository.findById(SystemSetting.KEY_CONSULTATION_FEE))
                .thenReturn(Optional.of(setting("free")));

        assertThat(service.getConsultationFee()).isEqualByComparingTo("500.00");
    }

    @Test
    void getConsultationFee_returnsPersistedValueScaledToTwoDecimals() {
        when(systemSettingRepository.findById(SystemSetting.KEY_CONSULTATION_FEE))
                .thenReturn(Optional.of(setting("750.5")));

        BigDecimal fee = service.getConsultationFee();

        assertThat(fee).isEqualByComparingTo("750.50");
        assertThat(fee.scale()).isEqualTo(2);
    }

    @Test
    void updateConsultationFee_persistsNewFee() {
        when(systemSettingRepository.findById(SystemSetting.KEY_CONSULTATION_FEE))
                .thenReturn(Optional.empty());

        ConsultationFeeResponse response =
                service.updateConsultationFee(new UpdateConsultationFeeRequest(new BigDecimal("650.755")));

        assertThat(response.consultationFee()).isEqualByComparingTo("650.76"); // HALF_UP

        ArgumentCaptor<SystemSetting> saved = ArgumentCaptor.forClass(SystemSetting.class);
        verify(systemSettingRepository).save(saved.capture());
        assertThat(saved.getValue().getSettingKey()).isEqualTo(SystemSetting.KEY_CONSULTATION_FEE);
        assertThat(saved.getValue().getSettingValue()).isEqualTo("650.76");
    }

    // ------------------------------------------------------------------
    // next-run date arithmetic (month-length edge cases)
    // ------------------------------------------------------------------

    @Test
    void nextRunDate_sameMonthWhenDayHasNotPassed() {
        assertThat(SystemSettingServiceImpl.nextRunDate(LocalDate.of(2026, 10, 20), 25))
                .isEqualTo(LocalDate.of(2026, 10, 25));
    }

    @Test
    void nextRunDate_rollsToNextMonthWhenDayHasPassed() {
        assertThat(SystemSettingServiceImpl.nextRunDate(LocalDate.of(2026, 10, 20), 5))
                .isEqualTo(LocalDate.of(2026, 11, 5));
    }

    @Test
    void nextRunDate_day31ClampsToAprilLastDay() {
        assertThat(SystemSettingServiceImpl.nextRunDate(LocalDate.of(2026, 4, 1), 31))
                .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void nextRunDate_day31ClampsToFebruaryLastDay() {
        assertThat(SystemSettingServiceImpl.nextRunDate(LocalDate.of(2026, 2, 1), 31))
                .isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void nextRunDate_day31ClampsToLeapFebruaryLastDay() {
        assertThat(SystemSettingServiceImpl.nextRunDate(LocalDate.of(2028, 2, 1), 31))
                .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    private SystemSetting setting(String value) {
        SystemSetting setting = new SystemSetting();
        setting.setSettingValue(value);
        return setting;
    }
}
