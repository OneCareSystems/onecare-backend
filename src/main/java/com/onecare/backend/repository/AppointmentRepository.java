package com.onecare.backend.repository;

import com.onecare.backend.entity.Appointment;
import com.onecare.backend.enums.AppointmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    List<Appointment> findByAppointmentDateOrderByTimeSlotAscAppointmentIdAsc(LocalDate appointmentDate);

    List<Appointment> findByDoctor_UserIdAndAppointmentDate(Long doctorId, LocalDate appointmentDate);

    List<Appointment> findByDoctor_UserId(Long doctorId);

    List<Appointment> findByPatient_PatientIdOrderByAppointmentDateDescTimeSlotDesc(Long patientId);
  
    List<Appointment> findByAppointmentDateAndStatus(LocalDate appointmentDate, AppointmentStatus status);

    /** Report aggregation (FR039): one grouped count over appointment_date, no N+1. */
    @Query("""
            SELECT a.status, COUNT(a)
            FROM Appointment a
            WHERE a.appointmentDate BETWEEN :from AND :to
            GROUP BY a.status
            """)
    List<Object[]> countByStatusBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Report aggregation (FR039): same grouped count narrowed to one doctor. */
    @Query("""
            SELECT a.status, COUNT(a)
            FROM Appointment a
            WHERE a.appointmentDate BETWEEN :from AND :to
              AND a.doctor.userId = :doctorId
            GROUP BY a.status
            """)
    List<Object[]> countByStatusBetweenForDoctor(@Param("from") LocalDate from,
                                                 @Param("to") LocalDate to,
                                                 @Param("doctorId") Long doctorId);

    /** Report aggregation (FR039): distinct patients seen in the range. */
    @Query("""
            SELECT COUNT(DISTINCT a.patient.patientId)
            FROM Appointment a
            WHERE a.appointmentDate BETWEEN :from AND :to
            """)
    long countDistinctPatientsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Report aggregation (FR039): distinct patients of one doctor in the range. */
    @Query("""
            SELECT COUNT(DISTINCT a.patient.patientId)
            FROM Appointment a
            WHERE a.appointmentDate BETWEEN :from AND :to
              AND a.doctor.userId = :doctorId
            """)
    long countDistinctPatientsBetweenForDoctor(@Param("from") LocalDate from,
                                               @Param("to") LocalDate to,
                                               @Param("doctorId") Long doctorId);
}
