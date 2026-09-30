package com.onecare.backend.repository;

import com.onecare.backend.entity.Medicine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface MedicineRepository extends JpaRepository<Medicine, Long> {

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
}