package com.onecare.backend.service;

import com.onecare.backend.dto.AppointmentConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Placeholder implementation of {@link AppointmentConfirmationNotifier} used
 * until the SMS/email provider is integrated (DDP-33): it logs the dispatch
 * instead of sending anything, so the confirmation call site is exercised in
 * tests and production without a live external dependency. Swap this bean for
 * the real provider when it lands.
 */
@Component
public class StubAppointmentConfirmationNotifier implements AppointmentConfirmationNotifier {

    private static final Logger log = LoggerFactory.getLogger(StubAppointmentConfirmationNotifier.class);

    @Override
    public void sendBookingConfirmation(AppointmentConfirmation confirmation) {
        log.info("Appointment confirmation dispatch (stub — SMS/email provider not wired yet) "
                        + "| appointmentId={} | patient={} | contact={} | slot={} {}",
                confirmation.appointmentId(),
                confirmation.patientFullName(),
                confirmation.patientContactNo(),
                confirmation.appointmentDate(),
                confirmation.timeSlot());
    }
}
