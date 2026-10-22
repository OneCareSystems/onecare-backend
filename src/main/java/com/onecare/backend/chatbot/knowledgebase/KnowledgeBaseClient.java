package com.onecare.backend.chatbot.knowledgebase;

interface KnowledgeBaseClient {

    KnowledgeDocumentResponse submitLink(String url, Long actorId);

    default KnowledgeDocumentResponse uploadPdf(org.springframework.web.multipart.MultipartFile file, String title, Long actorId) {
        throw new UnsupportedOperationException();
    }
    default KnowledgeDocumentPage list(int page, int pageSize, String sourceType, String status, Long actorId) {
        throw new UnsupportedOperationException();
    }
    default KnowledgeDocumentResponse get(long id, Long actorId) { throw new UnsupportedOperationException(); }
    default void remove(long id, Long actorId) { throw new UnsupportedOperationException(); }
}
