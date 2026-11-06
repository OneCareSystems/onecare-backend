package com.onecare.backend.repository;

import com.onecare.backend.entity.DispensingItem;
import com.onecare.backend.enums.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Read-side queries for the pharmacy reports (FR040) over the DDP-25
 * dispensing data. Every query aggregates or projects in a single statement
 * (no N+1) and filters on the indexed dispense_date.
 */
public interface DispensingItemRepository extends JpaRepository<DispensingItem, Long> {

    /** Units and events per medicine actually handed out in the range (DDP-25). */
    @Query("""
            SELECT di.medicine, SUM(di.quantityDispensed), COUNT(DISTINCT di.dispensing.dispenseId)
            FROM DispensingItem di
            WHERE di.medicine IS NOT NULL
              AND di.dispensing.status IN :statuses
              AND di.dispensing.dispenseDate >= :from
              AND di.dispensing.dispenseDate < :toExclusive
            GROUP BY di.medicine
            ORDER BY SUM(di.quantityDispensed) DESC
            """)
    List<Object[]> aggregateStockUsage(@Param("from") LocalDateTime from,
                                       @Param("toExclusive") LocalDateTime toExclusive,
                                       @Param("statuses") List<Status> statuses);

    /**
     * Dispensing history rows (DDP-25): one row per handed-over item. The two
     * nullable item associations are explicit LEFT JOINs so rows without a
     * medicine or prescription item are never filtered out, and patient
     * name/contact come from correlated subqueries on the denormalized
     * patient_id - no per-row lookup in the service.
     */
    @Query("""
            SELECT d.dispenseId, d.dispenseDate,
                   d.patientId,
                   (SELECT p.fullName FROM Patient p WHERE p.patientId = d.patientId),
                   (SELECT p.contactNo FROM Patient p WHERE p.patientId = d.patientId),
                   COALESCE(m.name, pi.medicineName, 'External purchase'),
                   di.quantityDispensed
            FROM DispensingItem di
            JOIN di.dispensing d
            LEFT JOIN di.medicine m
            LEFT JOIN di.prescriptionItem pi
            WHERE d.status IN :statuses
              AND d.dispenseDate >= :from
              AND d.dispenseDate < :toExclusive
            ORDER BY d.dispenseDate DESC, d.dispenseId DESC
            """)
    List<Object[]> findDispensingHistoryRows(@Param("from") LocalDateTime from,
                                             @Param("toExclusive") LocalDateTime toExclusive,
                                             @Param("statuses") List<Status> statuses);
}
