package com.onecare.backend.service;

import com.onecare.backend.dto.request.RestoreRequest;
import com.onecare.backend.dto.response.BackupResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

public interface BackupService {

    /** Runs a full, timestamped, AES-256-GCM encrypted export (FR010, UC5). */
    BackupResponse createBackup();

    /** All stored backups, newest first ( every entry has a timestamp). */
    List<BackupResponse> listBackups();

    /** The encrypted file of one backup, for download. */
    DownloadedBackup downloadBackup(Long backupId);

    /**
     * Destructive restore (UC5). Requires confirm=true ; source is
     * either a stored backup id or an uploaded encrypted file. The audit
     * entry is written only after the restore has completed.
     */
    Map<String, Integer> restore(RestoreRequest request, MultipartFile uploadedFile);

    /** Download payload: stored filename plus the encrypted bytes. */
    record DownloadedBackup(String filename, byte[] content) {
    }
}
