package com.onecare.backend.repository;

import com.onecare.backend.entity.PrescriptionItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrescriptionItemRepository extends JpaRepository<PrescriptionItem, Long> {
}
