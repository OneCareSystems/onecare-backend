package com.onecare.backend.service;

import com.onecare.backend.dto.request.UpdateBackupScheduleRequest;
import com.onecare.backend.dto.request.UpdateConsultationFeeRequest;
import com.onecare.backend.dto.response.BackupScheduleResponse;
import com.onecare.backend.dto.response.ConsultationFeeResponse;

import java.math.BigDecimal;

/**
 * Reads and updates runtime-configurable system settings persisted in the
 * {@code system_settings} table. Reads fall back to defaults when no row
 * exists, so the system works before the table is ever written to.
 */
public interface SystemSettingService {

    /** Day of month (1-31) on which the automatic monthly backup runs. */
    Integer getBackupDayOfMonth();

    /** Current backup schedule including the computed next run date. */
    BackupScheduleResponse getBackupSchedule();

    /** Updates the monthly backup day and returns the new schedule. */
    BackupScheduleResponse updateBackupSchedule(UpdateBackupScheduleRequest request);

    /** Current consultation fee used for newly generated invoices. */
    BigDecimal getConsultationFee();

    /** Current consultation fee setting. */
    ConsultationFeeResponse getConsultationFeeSetting();

    /** Updates the consultation fee and returns the new value. */
    ConsultationFeeResponse updateConsultationFee(UpdateConsultationFeeRequest request);
}
