package com.onecare.backend.scheduler;

import com.onecare.backend.service.BackupService;
import com.onecare.backend.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Runs the automatic monthly backup at end of day (23:59 by default) on the
 * day of the month configured in system settings. When the configured day
 * exceeds the current month's length the backup runs on the last day of the
 * month (e.g. day 31 -> Feb 28/29, Apr 30).
 */
@Component
public class SystemBackupScheduler {

    private static final Logger log = LoggerFactory.getLogger(SystemBackupScheduler.class);

    private final SystemSettingService systemSettingService;
    private final BackupService backupService;

    public SystemBackupScheduler(SystemSettingService systemSettingService,
                                 BackupService backupService) {
        this.systemSettingService = systemSettingService;
        this.backupService = backupService;
    }

    /** Daily job at the end of the day; triggers only on the configured day. */
    @Scheduled(cron = "${backup.monthly.cron:0 59 23 * * ?}")
    public void runMonthlyScheduledBackup() {
        runScheduledBackupIfDue(LocalDate.now());
    }

    /**
     * Creates a backup when {@code today} matches the configured day of month
     * (clamped to the month's length). Returns whether a backup was attempted.
     * Failures are logged and swallowed so the scheduler survives until the
     * next run.
     */
    public boolean runScheduledBackupIfDue(LocalDate today) {
        int configuredDay = systemSettingService.getBackupDayOfMonth();
        int effectiveDay = Math.min(configuredDay, today.lengthOfMonth());

        if (today.getDayOfMonth() != effectiveDay) {
            log.debug("Scheduled backup check | today={} configuredDay={} | not due",
                    today, configuredDay);
            return false;
        }

        log.info("Triggering automatic monthly backup | today={} | configuredDay={}",
                today, configuredDay);
        try {
            backupService.createBackup();
            return true;
        } catch (RuntimeException exception) {
            log.error("Automatic monthly backup failed | today={}", today, exception);
            return false;
        }
    }
}
