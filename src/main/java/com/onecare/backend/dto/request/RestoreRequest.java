package com.onecare.backend.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Restore request (UC5). Exactly one of backupId (restore a stored backup)
 * or an uploaded file must be supplied, and confirm=true is mandatory:
 * restore is destructive and silently overwriting data is forbidden.
 */
public record RestoreRequest(
        @JsonProperty("backupId") Long backupId,

        @JsonProperty("confirm") boolean confirm) {
}
