
package com.onecare.backend.repository;

import com.onecare.backend.entity.PrescriptionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface PrescriptionItemRepository
        extends JpaRepository<PrescriptionItem, Long> {

    /**
     * In-house dispensing units per medicine.
     * Excludes prescriptions that have an external dispensing event
     * to prevent duplicate stock-usage reporting.
     */
    @Query("""
            SELECT pi.medicine, SUM(pi.quantity)
            FROM PrescriptionItem pi
            WHERE pi.medicine IS NOT NULL
              AND pi.prescription.status =
                  com.onecare.backend.enums.PrescriptionStatus.DISPENSED
              AND pi.prescription.date BETWEEN :from AND :to
              AND NOT EXISTS (
                  SELECT 1
                  FROM ExternalDispensing ed
                  WHERE ed.prescription = pi.prescription
              )
            GROUP BY pi.medicine
            """)
    List<Object[]> aggregateDispensedStockUsage(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    /**
     * In-house dispensing history rows.
     * Excludes prescriptions represented by external dispensing events.
     * Retains items with no linked medicine using LEFT JOIN.
     */
    @Query("""
            SELECT pi.prescription.prescriptionId,
                   pi.prescription.date,
                   pi.prescription.patient.patientId,
                   pi.prescription.patient.fullName,
                   pi.prescription.patient.contactNo,
                   COALESCE(m.name, pi.medicineName),
                   pi.quantity
            FROM PrescriptionItem pi
            LEFT JOIN pi.medicine m
            WHERE pi.prescription.status =
                  com.onecare.backend.enums.PrescriptionStatus.DISPENSED
              AND pi.prescription.date BETWEEN :from AND :to
              AND NOT EXISTS (
                  SELECT 1
                  FROM ExternalDispensing ed
                  WHERE ed.prescription = pi.prescription
              )
            ORDER BY pi.prescription.date DESC,
                     pi.prescription.prescriptionId DESC
            """)
    List<Object[]> findDispensedItemRows(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
