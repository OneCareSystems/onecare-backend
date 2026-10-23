package com.onecare.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Generic key-value store for runtime-configurable system settings
 * (e.g. backup schedule day, consultation fee). Missing rows fall back
 * to defaults declared in {@code SystemSettingServiceImpl}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "system_settings")
public class SystemSetting {

    public static final String KEY_BACKUP_SCHEDULE_DAY_OF_MONTH = "BACKUP_SCHEDULE_DAY_OF_MONTH";
    public static final String KEY_CONSULTATION_FEE = "CONSULTATION_FEE";

    @Id
    @Column(name = "setting_key", length = 100)
    private String settingKey;

    @Column(name = "setting_value", nullable = false, length = 255)
    private String settingValue;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
