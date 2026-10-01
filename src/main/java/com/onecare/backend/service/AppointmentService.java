package com.onecare.backend.service;

import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;

import java.util.List;

public interface AppointmentService {
    AppointmentResponse createAppointment(CreateAppointmentRequest request);

    List<AppointmentResponse> listAppointments();

    AppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request);

    AppointmentResponse cancelAppointment(Long appointmentId);
}
