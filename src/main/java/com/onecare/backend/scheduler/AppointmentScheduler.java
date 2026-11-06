package com.onecare.backend.scheduler;

import com.onecare.backend.entity.Appointment;
import com.onecare.backend.enums.AppointmentStatus;
import com.onecare.backend.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled job to automatically transition unarrived, scheduled appointments to NO_SHOW
 * after each appointment's scheduled start and configured grace period.
 */
@Component
public class AppointmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentScheduler.class);

    private final AppointmentRepository appointmentRepository;
    private final Duration gracePeriod;

    public AppointmentScheduler(
            AppointmentRepository appointmentRepository,
            @Value("${clinic.noshow.grace-period-minutes:15}") long gracePeriodMinutes) {
        this.appointmentRepository = appointmentRepository;
        if (gracePeriodMinutes < 0) {
            throw new IllegalArgumentException("No-show grace period must not be negative");
        }
        this.gracePeriod = Duration.ofMinutes(gracePeriodMinutes);
    }

    /**
     * Checks regularly so appointments outside the clinic's default hours are
     * also transitioned after their individual grace periods expire.
     */
    @Scheduled(cron = "${clinic.noshow.cron:0 * * * * ?}")
    @Transactional
    public void checkNoShows() {
        LocalDateTime now = LocalDateTime.now();
        log.info("Running NO_SHOW scheduler at {} with grace period {}", now, gracePeriod);
        processDailyNoShows(now.toLocalDate(), now);
    }

    /**
     * Processes scheduled appointments whose appointment time plus grace
     * period has passed.
     * Only appointments with SCHEDULED status are updated to NO_SHOW.
     * Appointments in ARRIVED, COMPLETED, CANCELLED, or NO_SHOW are strictly ignored.
     */
    @Transactional
    public int processDailyNoShows(LocalDate date) {
        return processDailyNoShows(date, LocalDateTime.now());
    }

    int processDailyNoShows(LocalDate date, LocalDateTime now) {
        List<Appointment> eligible = appointmentRepository
                .findByAppointmentDateAndStatus(date, AppointmentStatus.SCHEDULED);

        int count = 0;
        for (Appointment appointment : eligible) {
            LocalDateTime noShowAfter = LocalDateTime.of(date, appointment.getTimeSlot()).plus(gracePeriod);
            if (now.isBefore(noShowAfter)) {
                continue;
            }
            appointment.setStatus(AppointmentStatus.NO_SHOW);
            appointmentRepository.save(appointment);
            count++;
        }

        log.info("Successfully updated {} appointments to NO_SHOW for date {} at {}", count, date, now);
        return count;
    }
}
