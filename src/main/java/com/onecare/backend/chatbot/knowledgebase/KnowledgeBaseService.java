package com.onecare.backend.chatbot.knowledgebase;

import com.onecare.backend.security.SecurityUtil;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

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
}
