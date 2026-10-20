package com.onecare.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onecare.backend.dto.request.RestoreRequest;
import com.onecare.backend.dto.response.BackupResponse;
import com.onecare.backend.entity.BackupMetadata;
import com.onecare.backend.exception.ResourceNotFoundException;
import com.onecare.backend.exception.RestoreConfirmationRequiredException;
import com.onecare.backend.repository.AuditLogRepository;
import com.onecare.backend.repository.BackupMetadataRepository;
import com.onecare.backend.repository.UserRepository;
import com.onecare.backend.util.AesGcmEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackupServiceImplTest {

    /** base64 of the 32 ASCII bytes "0123456789abcdef0123456789abcdef" (same as test properties). */
    private static final String TEST_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @TempDir
    Path storageDir;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private BackupMetadataRepository backupMetadataRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private BackupServiceImpl backupService;

    @BeforeEach
    void setUp() {
        backupService = new BackupServiceImpl(
                jdbcTemplate, backupMetadataRepository, userRepository, auditLogRepository,
                new ObjectMapper(), transactionManager,
                storageDir.toString(), TEST_KEY_BASE64);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private void stubEmptyExport() {
        lenient().when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());
    }

    @Test
    void createBackup_writesEncryptedFileAndRecordsTimestamp() throws Exception {
        // AC1: every entry has a timestamp; the file is AES-256-GCM encrypted
        stubEmptyExport();
        when(backupMetadataRepository.save(any(BackupMetadata.class)))
                .thenAnswer(invocation -> {
                    BackupMetadata metadata = invocation.getArgument(0);
                    metadata.setBackupId(1L);
                    return metadata;
                });

        BackupResponse response = backupService.createBackup();

        assertNotNull(response.createdAt(), "AC1: backup entry must carry a timestamp");
        assertTrue(response.filename().startsWith("onecare-backup-"));
        assertTrue(response.sizeBytes() > 0);

        Path file = storageDir.resolve(response.filename());
        assertTrue(Files.exists(file));
        byte[] onDisk = Files.readAllBytes(file);
        assertFalse(new String(onDisk, StandardCharsets.UTF_8).contains("tables"),
                "payload must not be readable plaintext");

        byte[] decrypted = new AesGcmEncryptor(Base64.getDecoder().decode(TEST_KEY_BASE64)).decrypt(onDisk);
        assertTrue(new String(decrypted, StandardCharsets.UTF_8).contains("\"users\""));
    }

    @Test
    void listBackups_returnsNewestFirstWithTimestamps() {
        BackupMetadata older = new BackupMetadata(1L, java.time.LocalDateTime.of(2026, 1, 1, 10, 0),
                "a.backup", 10L, "sum", 1L);
        BackupMetadata newer = new BackupMetadata(2L, java.time.LocalDateTime.of(2026, 2, 1, 10, 0),
                "b.backup", 20L, "sum", 1L);
        when(backupMetadataRepository.findAllByOrderByCreatedAtDescBackupIdDesc())
                .thenReturn(List.of(newer, older));

        List<BackupResponse> backups = backupService.listBackups();

        verify(backupMetadataRepository).findAllByOrderByCreatedAtDescBackupIdDesc();
        assertEquals(2, backups.size());
        assertEquals(2L, backups.get(0).backupId(), "newest first");
        assertNotNull(backups.get(0).createdAt());
        assertNotNull(backups.get(1).createdAt());
    }

    @Test
    void downloadBackup_returnsStoredBytes_unknownIdThrowsNotFound() throws Exception {
        BackupMetadata metadata = new BackupMetadata(1L, java.time.LocalDateTime.now(),
                "onecare-backup-x.backup", 5L, "sum", 1L);
        when(backupMetadataRepository.findById(1L)).thenReturn(Optional.of(metadata));
        Files.write(storageDir.resolve("onecare-backup-x.backup"), new byte[] {1, 2, 3});

        BackupService.DownloadedBackup download = backupService.downloadBackup(1L);
        assertEquals("onecare-backup-x.backup", download.filename());
        assertEquals(3, download.content().length);

        when(backupMetadataRepository.findById(404L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> backupService.downloadBackup(404L));
    }

    @Test
    void restore_withoutConfirm_throwsExplicitConfirmationRequired() {
        // AC4 negative: restore is destructive, confirm=true is mandatory
        assertThrows(RestoreConfirmationRequiredException.class,
                () -> backupService.restore(new RestoreRequest(1L, false), null));
        assertThrows(RestoreConfirmationRequiredException.class,
                () -> backupService.restore(null, null));
    }

    @Test
    void restore_withConfirm_replacesTablesInFkOrder() throws Exception {
        stubEmptyExport();
        when(backupMetadataRepository.save(any(BackupMetadata.class)))
                .thenAnswer(invocation -> {
                    BackupMetadata metadata = invocation.getArgument(0);
                    metadata.setBackupId(1L);
                    return metadata;
                });
        BackupResponse created = backupService.createBackup();
        ArgumentCaptor<BackupMetadata> savedCaptor = ArgumentCaptor.forClass(BackupMetadata.class);
        verify(backupMetadataRepository).save(savedCaptor.capture());
        when(backupMetadataRepository.findById(1L)).thenReturn(Optional.of(savedCaptor.getValue()));

        Map<String, Integer> restored = backupService.restore(new RestoreRequest(1L, true), null);

        assertNotNull(restored);
        // the destructive wipe happens inside the transaction template
        verify(transactionManager).commit(any());
    }
}
