package com.onecare.backend.repository;

import com.onecare.backend.entity.Prescription;
import com.onecare.backend.enums.PrescriptionStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PrescriptionRepository extends JpaRepository<Prescription, Long> {

    // items are fetched eagerly with the prescription to avoid N+1 on list/detail
    @Override
    @EntityGraph(attributePaths = "items")
    List<Prescription> findAll();

    @EntityGraph(attributePaths = "items")
    List<Prescription> findByStatus(PrescriptionStatus status);

    @Override
    @EntityGraph(attributePaths = "items")
    Optional<Prescription> findById(Long id);
}
