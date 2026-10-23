package com.onecare.backend.chatbot.knowledgebase;

import com.onecare.backend.security.SecurityUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import com.onecare.backend.chatbot.exception.InvalidInputException;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseClient chatbot;
    private final ApprovedUrlValidator urlValidator;
    private final KnowledgeBaseSubmission submission;

    public KnowledgeBaseService(
            KnowledgeBaseClient chatbot,
            ApprovedUrlValidator urlValidator,
            KnowledgeBaseSubmission submission
    ) {
        this.chatbot = chatbot;
        this.urlValidator = urlValidator;
        this.submission = submission;
    }

    public KnowledgeDocumentResponse submitLink(String url) {

        String approvedUrl = urlValidator.validate(url);

       
        Long actorId = SecurityUtil.getCurrentUserId()
                .orElseThrow(() ->
                        new AccessDeniedException(
                                "Authentication is required."
                        )
                );

       
        if (!SecurityUtil.hasAnyRole("DOCTOR", "SUPER_ADMIN")) {
            throw new AccessDeniedException(
                    "Insufficient permissions."
            );
        }

        
        return submission.submitApprovedLink(approvedUrl, actorId);
    }

    private Long actor() {
        Long id = SecurityUtil.getCurrentUserId().orElseThrow(() -> new AccessDeniedException("Authentication is required."));
        if (!SecurityUtil.hasAnyRole("DOCTOR", "SUPER_ADMIN")) throw new AccessDeniedException("Insufficient permissions.");
        return id;
    }

    @com.onecare.backend.audit.Audited(action = "KB_PDF_UPLOADED", module = "CHATBOT")
    public KnowledgeDocumentResponse uploadPdf(MultipartFile file, String title) {
        Long actorId = actor();
        if (file == null || file.isEmpty()) throw new InvalidInputException("A PDF file is required.");
        return chatbot.uploadPdf(file, title, actorId); // Django remains authoritative for content/size validation.
    }

    public KnowledgeDocumentPage list(int page, int pageSize, String sourceType, String status) {
        Long actorId = actor();
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new InvalidInputException("Invalid pagination.");
        return chatbot.list(page, pageSize, sourceType, status, actorId);
    }

    public KnowledgeDocumentResponse get(long id) {
        Long actorId = actor();
        if (id < 1) throw new InvalidInputException("A positive resource ID is required.");
        return chatbot.get(id, actorId);
    }

    @com.onecare.backend.audit.Audited(action = "KB_RESOURCE_REMOVED", module = "CHATBOT")
    public void remove(long id) {
        Long actorId = actor();
        if (id < 1) throw new InvalidInputException("A positive resource ID is required.");
        chatbot.remove(id, actorId);
    }
}
