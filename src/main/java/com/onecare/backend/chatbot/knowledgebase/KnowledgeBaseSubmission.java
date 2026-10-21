package com.onecare.backend.chatbot.knowledgebase;

import com.onecare.backend.audit.Audited;
import org.springframework.stereotype.Service;

@Service
class KnowledgeBaseSubmission {

    private final KnowledgeBaseClient chatbot;

    KnowledgeBaseSubmission(KnowledgeBaseClient chatbot) {
        this.chatbot = chatbot;
    }

    @Audited(action = "KB_LINK_SUBMITTED", module = "CHATBOT")
    public KnowledgeDocumentResponse submitApprovedLink(String approvedUrl, Long actorId) {
        return chatbot.submitLink(approvedUrl, actorId);
    }
}
