package com.onecare.backend.service;

import com.onecare.backend.dto.response.AuditLogResponse;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class AuditLogServiceImpl implements AuditLogService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditLogRepository auditLogRepository;

    public AuditLogServiceImpl(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(Long userId, String actionType, String module,
                                         LocalDate dateFrom, LocalDate dateTo,
                                         Integer page, Integer size) {

        int pageNumber = page == null ? 0 : Math.max(page, 0);
        int pageSize = size == null
                ? DEFAULT_PAGE_SIZE
                : Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        // Newest first, always - the trail is append-only and never re-sorted by clients.
        Pageable pageable = PageRequest.of(
                pageNumber,
                pageSize,
                Sort.by(
                        Sort.Order.desc("timestamp"),
                        Sort.Order.desc("logId")
                )
        );

        Specification<AuditLog> specification = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (userId != null) {
                predicates.add(cb.equal(root.get("user").get("userId"), userId));
            }
            if (actionType != null && !actionType.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("actionType")),
                        actionType.trim().toUpperCase()));
            }
            if (module != null && !module.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("entityType")),
                        module.trim().toUpperCase()));
            }
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("timestamp"), dateFrom.atStartOfDay()));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThan(
                        root.get("timestamp"), dateTo.plusDays(1).atStartOfDay()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return auditLogRepository.findAll(specification, pageable)
                .map(AuditLogResponse::from);
    }
}
