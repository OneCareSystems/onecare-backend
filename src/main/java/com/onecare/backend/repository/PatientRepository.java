package com.onecare.backend.repository;

import com.onecare.backend.entity.Patient;
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
}
