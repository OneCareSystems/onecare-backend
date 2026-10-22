package com.onecare.backend.chatbot.knowledgebase;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record KnowledgeDocumentResponse(Long id, String status, String title, String sourceType,
        String language, Boolean needsReview, String originalFilename, String sourceUrl,
        String uploadedBy, String failureReason, String createdAt) {
    public KnowledgeDocumentResponse(Long id, String status) {
        this(id, status, null, null, null, null, null, null, null, null, null);
    }
}
