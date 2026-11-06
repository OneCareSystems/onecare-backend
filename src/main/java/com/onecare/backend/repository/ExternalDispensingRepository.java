package com.onecare.backend.repository;

import com.onecare.backend.entity.ExternalDispensing;
import com.onecare.backend.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExternalDispensingRepository extends JpaRepository<ExternalDispensing, Long> {

    /** Dispensing events of a prescription that actually handed medicine out. */
    List<ExternalDispensing> findByPrescription_PrescriptionIdAndStatus(
            Long prescriptionId, Status status);
}
