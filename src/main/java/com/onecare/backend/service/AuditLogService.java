package com.onecare.backend.service;

import com.onecare.backend.dto.response.AuditLogResponse;
import org.springframework.data.domain.Page;

import java.time.LocalDate;

/**
 * Read side of the append-only audit trail (FR041, FR042, UC26).
 * There is deliberately no create/update/delete operation here: records are
 * only ever written through {@link AuditService} and never modified.
 */
public interface AuditLogService {

    /**
     * Filterable, newest-first page of the audit trail.
     *
     * @param userId     filter by actor, {@code null} for any
     * @param actionType exact action type, case-insensitive, {@code null}/blank for any
     * @param module     module (= entityType), case-insensitive exact match, {@code null}/blank for any
     * @param dateFrom   inclusive lower bound of the timestamp (yyyy-MM-dd)
     * @param dateTo     inclusive upper bound of the timestamp (yyyy-MM-dd)
     * @param page       zero-based page index, defaults to 0
     * @param size       page size, defaults to 20, capped at 100
     * @return matching page; never {@code null} - an empty result is a page with
     *         no content, serialized as {@code data: []} with HTTP 200
     */
    Page<AuditLogResponse> search(Long userId, String actionType, String module,
                                  LocalDate dateFrom, LocalDate dateTo,
                                  Integer page, Integer size);
}
