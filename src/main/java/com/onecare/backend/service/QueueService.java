package com.onecare.backend.service;

import com.onecare.backend.dto.request.AppointmentStatusRequest;
import com.onecare.backend.dto.response.AppointmentResponse;
import com.onecare.backend.dto.response.QueueResponse;

import java.util.List;

public interface QueueService {
    List<QueueResponse> getQueueToday();

    AppointmentResponse updateQueueStatus(Long appointmentId, AppointmentStatusRequest request);
}
