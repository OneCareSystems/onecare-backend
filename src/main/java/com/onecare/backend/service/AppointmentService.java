package com.onecare.backend.service;

import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.request.CreateAppointmentRequest;
import com.onecare.backend.dto.request.UpdateAppointmentRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;

import java.util.List;

public interface AppointmentService {
    AppointmentResponse createAppointment(CreateAppointmentRequest request);

    List<AppointmentResponse> listAppointments();

    AppointmentResponse updateAppointment(Long appointmentId, UpdateAppointmentRequest request);

    AppointmentResponse cancelAppointment(Long appointmentId);

    List<QueueResponse> getQueueToday();

    AppointmentResponse updateQueueStatus(Long appointmentId, AppointmentStatusRequest request);
}
