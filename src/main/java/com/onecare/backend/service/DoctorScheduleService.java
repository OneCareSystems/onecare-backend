package com.onecare.backend.service;

import com.onecare.backend.dto.request.DoctorScheduleRequest;
import com.onecare.backend.dto.response.DoctorScheduleResponse;

public interface DoctorScheduleService {

    /** The doctor's configured weekly schedule (empty lists when none is set). */
    DoctorScheduleResponse getSchedule(Long doctorId);

    /** Replaces the doctor's schedule after validating for slot conflicts (UC4). */
    DoctorScheduleResponse saveSchedule(Long doctorId, DoctorScheduleRequest request);
}
