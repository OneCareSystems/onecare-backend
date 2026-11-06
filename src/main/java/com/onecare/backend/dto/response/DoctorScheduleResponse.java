package com.onecare.backend.dto.response;

import java.util.List;

public record DoctorScheduleResponse(
        Long doctorId,
        List<DayScheduleResponse> days
) {
}