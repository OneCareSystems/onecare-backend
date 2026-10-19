package com.onecare.backend.service;

import com.onecare.backend.dto.AppointmentConfirmation;

/**
 * Appointment-booking confirmation channel.
 *
 * The SMS/email provider is not live yet; this interface keeps the booking
 * flow decoupled from the transport so wiring the real provider later is a
 * one-bean swap, not a redesign. Implementations must not throw in a way that
 * fails an already-persisted booking — the caller (PublicAppointmentServiceImpl)
 * also guards against it.
 */
public interface AppointmentConfirmationNotifier {

    void sendBookingConfirmation(AppointmentConfirmation confirmation);
}
