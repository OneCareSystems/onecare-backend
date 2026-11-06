package com.onecare.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.dto.request.RestoreRequest;
import com.onecare.backend.dto.response.BackupResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.BackupMetadata;
import com.onecare.backend.exception.BusinessRuleException;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.RestoreConfirmationRequiredException;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.BackupMetadataRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.security.SecurityUtil;
import com.onecare.backend.util.AesGcmEncryptor;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Full-data backup and restore.
 *
 * Backups are exported as JSON, encrypted with AES-256-GCM (SDS 2.5.3) and
 * written to {@code backup.storage-dir}; only metadata is kept in the
 * {@code backup_metadata} table. Restore is destructive (no silent
 * overwrite): it demands confirm=true, replaces every exported table inside
 * one transaction and writes its audit entry after completion (AC4).
 *
 * NFRR-004 (automated daily backups) is deliberately NOT implemented here —
 * it has no owning ticket yet.
 */
@Service
public class BackupServiceImpl implements BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupServiceImpl.class);
    
    private static final String FILE_PREFIX = "onecare-backup-";
    private static final String FILE_SUFFIX = ".backup";
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS");   // add millis
    /**
     * Exported tables in FK-safe DELETE order (children first). The restore
     * inserts them in reverse. backup_metadata is intentionally excluded so
     * a restore never wipes the catalog of the backup it came from.
     */
    private static final List<String> TABLES_CHILD_FIRST = List.of(
            "payments",
            "invoice_items",
            "invoices",
            "dispensing_items",
            "external_dispensing",
            "prescription_items",
            "prescriptions",
            "appointments",
            "medicines",
            "patients",
            "password_reset_tokens",
            "audit_log",
            "doctor_schedules",
            "users",
            "role_permissions",
            "system_settings");

    private static final int PAYLOAD_VERSION = 1;

    private final JdbcTemplate jdbcTemplate;
    private final BackupMetadataRepository backupMetadataRepository;
    private final UserRepository userRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate snapshotTransactionTemplate;
    private final Path storageDir;
    private final String encryptionKeyBase64;

    private volatile AesGcmEncryptor encryptor;

    public BackupServiceImpl(JdbcTemplate jdbcTemplate,
            BackupMetadataRepository backupMetadataRepository,
            UserRepository userRepository,
            AuditLogRepository auditLogRepository,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            @Value("${backup.storage-dir:./backups}") String storageDir,
            @Value("${backup.encryption-key:}") String encryptionKeyBase64) {
        this.jdbcTemplate = jdbcTemplate;
        this.backupMetadataRepository = backupMetadataRepository;
        this.userRepository = userRepository;
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.snapshotTransactionTemplate = new TransactionTemplate(transactionManager);
        this.snapshotTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.snapshotTransactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTransactionTemplate.setReadOnly(true);
        this.storageDir = Paths.get(storageDir);
        this.encryptionKeyBase64 = encryptionKeyBase64;
    }

    @PostConstruct
    void initStorageDir() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot create backup storage directory: " + storageDir, exception);
        }
    }

    @Override
    public BackupResponse createBackup() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", PAYLOAD_VERSION);
        payload.put("createdAt", LocalDateTime.now().toString());
        payload.put("tables", exportTables());

        byte[] encrypted;
        try {
            encrypted = encryptor().encrypt(objectMapper.writeValueAsBytes(payload));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to serialize backup payload", exception);
        }
        
        LocalDateTime createdAt = LocalDateTime.now();
        String filename = FILE_PREFIX
                + createdAt.format(FILE_STAMP)
                + "-" + UUID.randomUUID().toString().substring(0, 8)
                + FILE_SUFFIX;
        Path file = storageDir.resolve(filename);
        try {
            Files.write(file, encrypted);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write backup file: " + file, exception);
        }

        BackupMetadata metadata = new BackupMetadata();
        metadata.setCreatedAt(createdAt);
        metadata.setFilename(filename);
        metadata.setSizeBytes((long) encrypted.length);
        metadata.setChecksum(sha256Hex(encrypted));
        metadata.setCreatedBy(SecurityUtil.getCurrentUserId().orElse(null));
        BackupMetadata saved = backupMetadataRepository.save(metadata);

        writeAudit("BACKUP_CREATE", "Created backup " + filename + " (" + encrypted.length + " bytes, encrypted)");
        log.info("Created backup {} ({} bytes)", filename, encrypted.length);
        return toResponse(saved);
    }

    @Override
    public List<BackupResponse> listBackups() {
        return backupMetadataRepository.findAllByOrderByCreatedAtDescBackupIdDesc().stream()
                .map(BackupServiceImpl::toResponse)
                .toList();
    }

    @Override
    public DownloadedBackup downloadBackup(Long backupId) {
        BackupMetadata metadata = backupMetadataRepository.findById(backupId)
                .orElseThrow(() -> new ResourceNotFoundException("Backup not found with id: " + backupId));

        Path file = storageDir.resolve(metadata.getFilename());
        if (!Files.exists(file)) {
            throw new ResourceNotFoundException("Backup file is missing on disk: " + metadata.getFilename());
        }
        try {
            return new DownloadedBackup(metadata.getFilename(), Files.readAllBytes(file));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to read backup file: " + file, exception);
        }
    }

    @Override
    public Map<String, Integer> restore(RestoreRequest request, MultipartFile uploadedFile) {
        // UC5 / AC4: destructive restore always needs explicit confirmation
        if (request == null || !request.confirm()) {
            throw new RestoreConfirmationRequiredException();
        }

        byte[] encrypted;
        String source;
        if (uploadedFile != null && !uploadedFile.isEmpty()) {
            try {
                encrypted = uploadedFile.getBytes();
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read uploaded backup file", exception);
            }
            source = "uploaded file";
        } else if (request.backupId() != null) {
            BackupMetadata metadata = backupMetadataRepository.findById(request.backupId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Backup not found with id: " + request.backupId()));
            Path file = storageDir.resolve(metadata.getFilename());
            if (!Files.exists(file)) {
                throw new ResourceNotFoundException("Backup file is missing on disk: " + metadata.getFilename());
            }
            try {
                encrypted = Files.readAllBytes(file);
            } catch (IOException exception) {
                throw new UncheckedIOException("Unable to read backup file: " + file, exception);
            }
            if (!sha256Hex(encrypted).equals(metadata.getChecksum())) {
                throw new BusinessRuleException("Backup file failed checksum verification: " + metadata.getFilename());
            }
            source = "backup " + request.backupId();
        } else {
            throw new BusinessRuleException("Provide a backupId or an uploaded backup file to restore");
        }

        Map<String, Object> payload = parsePayload(encrypted);

        // Destructive import: all tables replaced inside one transaction
        Map<String, Integer> restored = transactionTemplate.execute(status -> importTables(payload));

        // AC4: the audit entry is written only after the restore completed
        writeAudit("SYSTEM_RESTORE",
                "Restored system data from " + source + " (" + restored.size() + " table(s))");
        log.info("Restore from {} completed: {}", source, restored);
        return restored;
    }

    private Map<String, Object> parsePayload(byte[] encrypted) {
        byte[] json;
        try {
            json = encryptor().decrypt(encrypted);
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException(exception.getMessage());
        }
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            if (!(parsed instanceof Map<?, ?> map) || !(map.get("tables") instanceof Map<?, ?> tables)) {
                throw new BusinessRuleException("Invalid backup file: missing tables section");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("version", map.get("version"));
            payload.put("tables", tables);
            return payload;
        } catch (IOException exception) {
            throw new BusinessRuleException("Invalid backup file: unreadable payload");
        }
    }

    private Map<String, Object> exportTables() {
        return snapshotTransactionTemplate.execute(status -> {
            Map<String, Object> tables = new LinkedHashMap<>();
            for (String table : TABLES_CHILD_FIRST) {
                tables.put(table, normalizeRows(jdbcTemplate.queryForList("SELECT * FROM " + table)));
            }
            return tables;
        });
    }

    /**
     * Temporal values are exported as ISO-8601 strings so the JSON round-trip
     * stays lossless and re-importable on both MySQL and H2.
     */
    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalized = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> normalizedRow = new LinkedHashMap<>();
            row.forEach((column, value) -> normalizedRow.put(column, normalizeValue(value)));
            normalized.add(normalizedRow);
        }
        return normalized;
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof BigDecimal || value instanceof Number || value instanceof Boolean
                || value instanceof String || value == null) {
            return value;
        }
        return String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> importTables(Map<String, Object> payload) {
        Object tablesObject = payload.get("tables");
        Map<String, Object> tables = (Map<String, Object>) tablesObject;

        Map<String, Integer> restored = new TreeMap<>();

        // Children first so FKs never block the wipe
        for (String table : TABLES_CHILD_FIRST) {
            if (tables.containsKey(table)) {
                jdbcTemplate.update("DELETE FROM " + table);
            }
        }
        // Parents first for the re-insert
        for (int i = TABLES_CHILD_FIRST.size() - 1; i >= 0; i--) {
            String table = TABLES_CHILD_FIRST.get(i);
            if (!(tables.get(table) instanceof List<?> rows)) {
                continue;
            }
            int count = 0;
            for (Object rowObject : rows) {
                insertRow(table, (Map<String, Object>) rowObject);
                count++;
            }
            restored.put(table, count);
        }
        return restored;
    }

    private void insertRow(String table, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return;
        }
        assertSafeIdentifier(table);
        StringBuilder columns = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        List<Object> values = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            assertSafeIdentifier(entry.getKey());
            if (!first) {
                columns.append(", ");
                placeholders.append(", ");
            }
            first = false;
            columns.append(entry.getKey());
            placeholders.append("?");
            values.add(entry.getValue());
        }
        jdbcTemplate.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + placeholders + ")",
                values.toArray());
    }

    /** Identifiers come from our own schema; guard anyway before concatenating. */
    private void assertSafeIdentifier(String identifier) {
        if (identifier == null || !identifier.matches("[a-z_][a-z0-9_]*")) {
            throw new BusinessRuleException("Invalid backup file: unsafe identifier " + identifier);
        }
    }

    private AesGcmEncryptor encryptor() {
        if (encryptor == null) {
            synchronized (this) {
                if (encryptor == null) {
                    if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
                        throw new IllegalStateException(
                                "Backup encryption key is not configured (backup.encryption-key / BACKUP_ENCRYPTION_KEY)");
                    }
                    encryptor = new AesGcmEncryptor(Base64.getDecoder().decode(encryptionKeyBase64.trim()));
                }
            }
        }
        return encryptor;
    }

    private String sha256Hex(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }

    private void writeAudit(String action, String description) {
        SecurityUtil.getCurrentUserId()
                .flatMap(userRepository::findById)
                .ifPresent(actor -> {
                    AuditLog audit = new AuditLog();
                    audit.setUser(actor);
                    audit.setAction(action);
                    audit.setActionType("UPDATE");
                    audit.setTimestamp(LocalDateTime.now());
                    audit.setEntityType("System");
                    audit.setEntityId(action);
                    audit.setDescription(description);
                    auditLogRepository.save(audit);
                });
    }

    private static BackupResponse toResponse(BackupMetadata metadata) {
        return new BackupResponse(
                metadata.getBackupId(),
                metadata.getCreatedAt(),
                metadata.getFilename(),
                metadata.getSizeBytes());
    }
}
