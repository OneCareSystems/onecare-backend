package com.onecare.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One working window or explicit day-off marker in a doctor's weekly schedule
 * (FR009). Unavailable rows have {@code available=false} and no time window.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "doctor_schedules", uniqueConstraints = {
        @UniqueConstraint(name = "uq_doctor_schedule", columnNames = {"doctor_id", "day_of_week", "start_time", "end_time"})
})
public class DoctorSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "doctor_id", nullable = false)
    private User doctor;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    @Column(name = "available", nullable = false, columnDefinition = "BOOLEAN NOT NULL DEFAULT TRUE")
    private boolean available = true;
}
