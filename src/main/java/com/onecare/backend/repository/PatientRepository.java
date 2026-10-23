package com.onecare.backend.repository;

import com.onecare.backend.entity.Patient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PatientRepository extends JpaRepository<Patient, Long>  {
   boolean existsByFullNameIgnoreCaseAndContactNo(
            String fullName,
            String contactNo
    );

    boolean existsByFullNameIgnoreCaseAndContactNoIn(
            String fullName,
            List<String> contactNos
    );

    /**
     * duplicate check (same name + contact-number
     * semantics) — used by public self-booking to reuse an existing
     * patient record instead of creating a duplicate.
     */
    Optional<Patient> findFirstByFullNameIgnoreCaseAndContactNo(
            String fullName,
            String contactNo
    );

    List<Patient> findByFullNameIgnoreCase(String fullName);

    List<Patient> findByContactNoInAndIsActiveTrue(List<String> contactNos);

      @Query("SELECT p FROM Patient p WHERE p.isActive = true AND " +
       "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :search, '%'))")
    List<Patient> searchByName(@Param("search") String search);

    /**
     * Paginated search over active patients: partial case-insensitive name,
     * exact contact number (in any stored format — canonical, legacy spaced or
     * as typed, passed in as {@code contactVariants}), or exact patient ID.
     * Pageable drives the DB offset/limit and the sort; the count query is
     * skipped (List return type).
     */
    @Query("SELECT p FROM Patient p WHERE p.isActive = true AND (" +
            "LOWER(p.fullName) LIKE LOWER(CONCAT('%', :search, '%')) " +
            "OR p.contactNo IN :contactVariants " +
            "OR CAST(p.patientId AS string) = :search)")
    List<Patient> search(@Param("search") String search,
                         @Param("contactVariants") List<String> contactVariants,
                         Pageable pageable);
}
