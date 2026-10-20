package com.onecare.backend.repository;

import com.onecare.backend.entity.BackupMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BackupMetadataRepository extends JpaRepository<BackupMetadata, Long> {
    List<BackupMetadata> findAllByOrderByCreatedAtDescBackupIdDesc();
}
