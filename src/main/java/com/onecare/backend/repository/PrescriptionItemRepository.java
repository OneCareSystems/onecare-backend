package com.onecare.backend.repository;

import com.onecare.backend.entity.PrescriptionItem;
import com.onecare.backend.enums.PrescriptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {

    /**
     * In-house dispensing (DDP-30) units per medicine: items of prescriptions
     * marked DISPENSED in the range. Prescriptions that also produced an
     * external dispensing event (DDP-25) are excluded so the same handover is
     * never counted twice in the stock-usage report (FR040).
     */
    @Query("""
            SELECT pi.medicine, SUM(pi.quantity)
            FROM PrescriptionItem pi
            WHERE pi.medicine IS NOT NULL
              AND pi.prescription.status = com.onecare.backend.enums.PrescriptionStatus.DISPENSED
              AND pi.prescription.date BETWEEN :from AND :to
              AND NOT EXISTS (
                  SELECT 1 FROM ExternalDispensing ed
                  WHERE ed.prescription = pi.prescription
              )
            GROUP BY pi.medicine
            """)
    List<Object[]> aggregateDispensedStockUsage(@Param("from") LocalDate from,
                                                 @Param("to") LocalDate to);

    /**
     * In-house dispensing (DDP-30) history rows: one row per dispensed item.
     * medicine is LEFT JOINed (nullable for EXTERNAL_PURCHASE items) so those
     * rows survive; the patient is reached through the prescription in the
     * same statement.
     */
    @Query("""
            SELECT pi.prescription.prescriptionId, pi.prescription.date,
                   pi.prescription.patient.patientId, pi.prescription.patient.fullName,
                   pi.prescription.patient.contactNo,
                   COALESCE(m.name, pi.medicineName),
                   pi.quantity
            FROM PrescriptionItem pi
            LEFT JOIN pi.medicine m
            WHERE pi.prescription.status = com.onecare.backend.enums.PrescriptionStatus.DISPENSED
              AND pi.prescription.date BETWEEN :from AND :to
            ORDER BY pi.prescription.date DESC, pi.prescription.prescriptionId DESC
            """)
    List<Object[]> findDispensedItemRows(@Param("from") LocalDate from,
                                          @Param("to") LocalDate to);
}
