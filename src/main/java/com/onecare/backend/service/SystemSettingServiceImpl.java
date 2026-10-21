package com.onecare.backend.service;

import com.onecare.backend.dto.request.UpdateBackupScheduleRequest;
import com.onecare.backend.dto.request.UpdateConsultationFeeRequest;
import com.onecare.backend.dto.response.BackupScheduleResponse;
import com.onecare.backend.dto.response.ConsultationFeeResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.SystemSetting;
import com.onecare.backend.entity.User;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.SystemSettingRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class SystemSettingServiceImpl implements SystemSettingService {

    private static final Logger log = LoggerFactory.getLogger(SystemSettingServiceImpl.class);

    /** Used when no BACKUP_SCHEDULE_DAY_OF_MONTH row exists yet. */
    static final int DEFAULT_BACKUP_SCHEDULE_DAY_OF_MONTH = 28;

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private final SystemSettingRepository systemSettingRepository;
    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final BigDecimal defaultConsultationFee;
    private final String executionTime;

    public SystemSettingServiceImpl(SystemSettingRepository systemSettingRepository,
                                    AuditLogRepository auditLogRepository,
                                    UserRepository userRepository,
                                    @Value("${billing.consultation-fee:500.00}") BigDecimal defaultConsultationFee,
                                    @Value("${backup.monthly.execution-time:23:59}") String executionTime) {
        this.systemSettingRepository = systemSettingRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.defaultConsultationFee = defaultConsultationFee;
        this.executionTime = executionTime;
    }

    // ------------------------------------------------------------------
    // Backup schedule
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Integer getBackupDayOfMonth() {
        return readInt(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH)
                .filter(day -> day >= 1 && day <= 31)
                .orElse(DEFAULT_BACKUP_SCHEDULE_DAY_OF_MONTH);
    }

    @Override
    @Transactional(readOnly = true)
    public BackupScheduleResponse getBackupSchedule() {
        int day = getBackupDayOfMonth();
        return new BackupScheduleResponse(day, executionTime, nextRunDate(LocalDate.now(), day));
    }

    @Override
    public BackupScheduleResponse updateBackupSchedule(UpdateBackupScheduleRequest request) {
        int day = request.dayOfMonth(); // validated 1-31 by the DTO

        saveSetting(SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH,
                String.valueOf(day),
                "Day of month for automatic monthly backup at end of day (1-31)");
        log.info("Backup schedule updated | dayOfMonth={} | nextRun={}",
                day, nextRunDate(LocalDate.now(), day));
        recordAudit("UPDATE", SystemSetting.KEY_BACKUP_SCHEDULE_DAY_OF_MONTH,
                "Monthly backup schedule set to day " + day + " of the month");

        return new BackupScheduleResponse(day, executionTime, nextRunDate(LocalDate.now(), day));
    }

    // ------------------------------------------------------------------
    // Consultation fee
    // ------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getConsultationFee() {
        return readValue(SystemSetting.KEY_CONSULTATION_FEE)
                .flatMap(SystemSettingServiceImpl::parseMoney)
                .orElse(defaultConsultationFee)
                .setScale(MONEY_SCALE, MONEY_ROUNDING);
    }

    @Override
    @Transactional(readOnly = true)
    public ConsultationFeeResponse getConsultationFeeSetting() {
        return new ConsultationFeeResponse(getConsultationFee());
    }

    @Override
    public ConsultationFeeResponse updateConsultationFee(UpdateConsultationFeeRequest request) {
        BigDecimal fee = request.consultationFee().setScale(MONEY_SCALE, MONEY_ROUNDING);

        saveSetting(SystemSetting.KEY_CONSULTATION_FEE,
                fee.toPlainString(),
                "Standard consultation fee amount");
        log.info("Consultation fee updated | consultationFee={}", fee);
        recordAudit("UPDATE", SystemSetting.KEY_CONSULTATION_FEE,
                "Consultation fee set to " + fee.toPlainString());

        return new ConsultationFeeResponse(fee);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Next date the monthly backup runs on, given the configured day.
     * Days beyond a month's length run on that month's last day; when the
     * configured day already passed this month, the run rolls to next month.
     */
    static LocalDate nextRunDate(LocalDate from, int dayOfMonth) {
        LocalDate candidate = atEffectiveDay(from, dayOfMonth);
        return candidate.isBefore(from)
                ? atEffectiveDay(from.plusMonths(1), dayOfMonth)
                : candidate;
    }

    /** Clamps the configured day to the month's length (e.g. 31 -> 28 in February). */
    static LocalDate atEffectiveDay(LocalDate month, int dayOfMonth) {
        return month.withDayOfMonth(Math.min(dayOfMonth, month.lengthOfMonth()));
    }

    private Optional<String> readValue(String key) {
        return systemSettingRepository.findById(key).map(SystemSetting::getSettingValue);
    }

    private Optional<Integer> readInt(String key) {
        return readValue(key).flatMap(SystemSettingServiceImpl::parseInt);
    }

    private static Optional<Integer> parseInt(String raw) {
        try {
            return Optional.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException exception) {
            log.warn("Invalid integer system setting value '{}' | falling back to default", raw);
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> parseMoney(String raw) {
        try {
            return Optional.of(new BigDecimal(raw.trim()));
        } catch (NumberFormatException exception) {
            log.warn("Invalid decimal system setting value '{}' | falling back to default", raw);
            return Optional.empty();
        }
    }

    private void saveSetting(String key, String value, String description) {
        SystemSetting setting = systemSettingRepository.findById(key).orElseGet(SystemSetting::new);
        setting.setSettingKey(key);
        setting.setSettingValue(value);
        setting.setDescription(description);
        systemSettingRepository.save(setting);
    }

    /** Audit trail for settings changes; skipped when no authenticated user (e.g. tests). */
    private void recordAudit(String action, String entityId, String description) {
        Optional<User> actor = resolveAuthenticatedUser();
        if (actor.isEmpty()) {
            return;
        }
        AuditLog audit = new AuditLog();
        audit.setUser(actor.get());
        audit.setAction(action);
        audit.setActionType("DOMAIN_EVENT");
        audit.setEntityType("SystemSetting");
        audit.setEntityId(entityId);
        audit.setDescription(description);
        audit.setTimestamp(LocalDateTime.now());
        auditLogRepository.save(audit);
    }

    private Optional<User> resolveAuthenticatedUser() {
        Long userId = SecurityUtil.getCurrentUserId().orElse(null);
        return userId != null
                ? userRepository.findById(userId)
                : SecurityUtil.getCurrentUsername().flatMap(userRepository::findByUsername);
    }
}
