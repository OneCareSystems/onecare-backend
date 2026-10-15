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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Scheduled job to automatically transition unarrived, scheduled appointments to NO_SHOW
 * after the configured cutoff time (default 9:15 PM).
 */
@Component
public class AppointmentScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentScheduler.class);

    private final AppointmentRepository appointmentRepository;
    private final LocalTime cutoffTime;

    public AppointmentScheduler(
            AppointmentRepository appointmentRepository,
            @Value("${clinic.noshow.cutoff-time:21:15}") String cutoffTimeStr) {
        this.appointmentRepository = appointmentRepository;
        this.cutoffTime = LocalTime.parse(cutoffTimeStr);
    }

    /**
     * Daily scheduled job running at the configured cutoff time (default 9:15 PM)
     * to transition eligible SCHEDULED appointments to NO_SHOW.
     */
    @Scheduled(cron = "${clinic.noshow.cron:0 15 21 * * ?}")
    @Transactional
    public void scheduleDailyNoShowCheck() {
        log.info("Running daily NO_SHOW scheduler for appointments up to cutoff {}", cutoffTime);
        processDailyNoShows(LocalDate.now());
    }

    /**
     * Processes eligible appointments for the specified date.
     * Only appointments with SCHEDULED status are updated to NO_SHOW.
     * Appointments in ARRIVED, COMPLETED, CANCELLED, or NO_SHOW are strictly ignored.
     */
    @Transactional
    public int processDailyNoShows(LocalDate date) {
        List<Appointment> eligible = appointmentRepository
                .findByAppointmentDateAndStatus(date, AppointmentStatus.SCHEDULED);

        int count = 0;
        for (Appointment appointment : eligible) {
            appointment.setStatus(AppointmentStatus.NO_SHOW);
            appointmentRepository.save(appointment);
            count++;
        }

        log.info("Successfully updated {} appointments to NO_SHOW for date {}", count, date);
        return count;
    }

    public LocalTime getCutoffTime() {
        return cutoffTime;
    }
}
