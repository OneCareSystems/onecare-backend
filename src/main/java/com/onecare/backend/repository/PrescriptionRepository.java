package com.onecare.backend.repository;

import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.PrescriptionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    @org.springframework.data.jpa.repository.Query("select p.dispenseRevision from Prescription p where p.prescriptionId = :id")
    Optional<Long> findDispenseRevision(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @org.springframework.data.jpa.repository.Query("select p from Prescription p where p.prescriptionId = :id")
    Optional<Prescription> findByIdForDispensing(@org.springframework.data.repository.query.Param("id") Long id);

    // items are fetched eagerly with the prescription to avoid N+1 on list/detail
    @Override
    @EntityGraph(attributePaths = "items")
    List<Prescription> findAll();

    @EntityGraph(attributePaths = "items")
    List<Prescription> findByStatus(PrescriptionStatus status);

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<Prescription> findById(Long id);

    boolean existsByAppointmentAppointmentId(Long appointmentId);

    Optional<Prescription> findByAppointmentAppointmentId(Long appointmentId);

    @EntityGraph(attributePaths = "items")
    List<Prescription> findByDoctor_UserId(Long doctorId);

    @EntityGraph(attributePaths = "items")
    List<Prescription> findByDoctor_UserIdAndStatus(Long doctorId, PrescriptionStatus status);

    @EntityGraph(attributePaths = "items")
    List<Prescription> findByDoctor_UserIdAndDateAndStatusNot(
            Long doctorId,
            LocalDate date,
            PrescriptionStatus status);

    @EntityGraph(attributePaths = {"items", "doctor", "patient", "appointment"})
    List<Prescription> findByDateAndStatusNot(
            LocalDate date,
            PrescriptionStatus status);

    @EntityGraph(attributePaths = "items")
    List<Prescription> findByPatient_PatientId(Long patientId);
}
