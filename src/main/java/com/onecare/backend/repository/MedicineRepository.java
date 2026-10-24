package com.onecare.backend.repository;

import com.onecare.backend.entity.Medicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Medicine m where m.medicineId = :id")
    java.util.Optional<Medicine> findByIdForUpdate(@Param("id") Long id);

    List<Medicine> findByIsQuarantinedFalse();

    List<Medicine> findByExpiryDateBeforeAndIsQuarantinedFalse(
            LocalDate date
    );

    @Query("""
            SELECT m
            FROM Medicine m
            WHERE m.stockQuantity <= m.reorderLevel
            AND m.isQuarantined = false
            """)
    List<Medicine> findLowStockMedicines();

    @Query("""
            SELECT m
            FROM Medicine m
            WHERE m.expiryDate BETWEEN :today AND :nearExpiryDate
            AND m.isQuarantined = false
            """)
    List<Medicine> findNearExpiryMedicines(
            @Param("today") LocalDate today,
            @Param("nearExpiryDate") LocalDate nearExpiryDate
    );

    @Query("""
            SELECT m
            FROM Medicine m
            WHERE LOWER(m.name) LIKE LOWER(CONCAT('%', :search, '%'))
            OR CAST(m.medicineId AS string) = :search
            """)
    List<Medicine> search(@Param("search") String search);

    /**
     * Report query (FR040, AC4): straight projection of the expiry_date /
     * is_quarantined data DDP-21 maintains - quarantined rows are included so
     * the report reflects the flag rather than filtering it away. No separate
     * expiry logic runs here.
     */
    @Query("""
            SELECT m
            FROM Medicine m
            WHERE m.expiryDate BETWEEN :from AND :to
            ORDER BY m.expiryDate ASC
            """)
    List<Medicine> findExpiringBetween(@Param("from") LocalDate from,
                                       @Param("to") LocalDate to);
}
