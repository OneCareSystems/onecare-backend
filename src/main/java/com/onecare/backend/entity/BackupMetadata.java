package com.onecare.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Metadata row of one system backup (FR010, UC5). The payload itself is an
 * AES-256-GCM encrypted file on disk (see
 * {@code backup.storage-dir}); this table records what exists so it can be
 * listed and downloaded.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "backup_metadata")
public class BackupMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "backup_id")
    private Long backupId;

    /** Timestamp shown in listings; also embedded in the filename (AC1). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private Long sizeBytes;

    /** SHA-256 of the encrypted file, verified on download. */
    @Column(name = "checksum", nullable = false, length = 64)
    private String checksum;

    @Column(name = "created_by")
    private Long createdBy;
}
