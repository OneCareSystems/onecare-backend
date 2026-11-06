package com.onecare.backend.repository;

import com.onecare.backend.entity.DoctorSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;

public interface DoctorScheduleRepository extends JpaRepository<DoctorSchedule, Long> {

    List<DoctorSchedule> findByDoctorUserIdOrderByStartTimeAsc(Long doctorId);

    List<DoctorSchedule> findByDoctorUserIdAndDayOfWeekOrderByStartTimeAsc(Long doctorId, DayOfWeek dayOfWeek);

    void deleteByDoctorUserId(Long doctorId);
}
