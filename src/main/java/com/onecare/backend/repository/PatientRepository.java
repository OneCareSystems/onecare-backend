package com.onecare.backend.repository;

import com.onecare.backend.entity.Patient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PatientRepository extends JpaRepository<Patient, Long>  {
   boolean existsByFullNameIgnoreCaseAndContactNo(
            String fullName,
            String contactNo
    );
    
    Optional<Patient> findByContactNoAndIsActiveTrue(String contactNo);

      @Query("SELECT p FROM Patient p WHERE p.isActive = true AND " +
       "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<Patient> searchByName(@Param("search") String search);

    /**
     * Paginated search over active patients: partial case-insensitive name,
     * exact contact number, or exact patient ID. Pageable drives the DB
     * offset/limit and the sort; the count query is skipped (List return type).
     */
    @Query("SELECT p FROM Patient p WHERE p.isActive = true AND (" +
            "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR p.contactNo = :search " +
            "OR CAST(p.patientId AS string) = :search)")
    List<Patient> search(@Param("search") String search, Pageable pageable);
}
