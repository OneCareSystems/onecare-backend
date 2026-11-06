package com.onecare.backend.service;

import com.onecare.backend.audit.AuditSanitizer;
import com.onecare.backend.entity.AuditLog;
import com.onecare.backend.entity.User;
import com.onecare.backend.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final AuditLogRepository auditLogRepository;
    private final TransactionTemplate transactionTemplate;

    public AuditServiceImpl(AuditLogRepository auditLogRepository,
                            PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public AuditLog record(User user, String actionType, String module, String entityId,
                           String description, String ipAddress) {
        try {
            return transactionTemplate.execute(status -> {
                AuditLog audit = new AuditLog();
                audit.setUser(user);
                audit.setAction(actionType);
                audit.setActionType(actionType);
                audit.setEntityType(module);
                audit.setEntityId(entityId);
                audit.setDescription(AuditSanitizer.sanitize(description));
                audit.setIpAddress(ipAddress);
                audit.setTimestamp(LocalDateTime.now());
                return auditLogRepository.save(audit);
            });
        } catch (Exception ex) {
            log.error("Audit record failed | actionType={} | module={} | entityId={}",
                    actionType, module, entityId, ex);
            return null;
        }
    }
}
