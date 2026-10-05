package com.onecare.backend.repository;

import com.onecare.backend.entity.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByAppointmentDateOrderByTimeSlotAscAppointmentIdAsc(LocalDate appointmentDate);

    List<Appointment> findByDoctor_UserIdAndAppointmentDate(Long doctorId, LocalDate appointmentDate);
}
