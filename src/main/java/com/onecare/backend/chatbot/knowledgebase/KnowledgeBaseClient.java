package com.onecare.backend.chatbot.knowledgebase;

interface KnowledgeBaseClient {

    KnowledgeDocumentResponse submitLink(String url, Long actorId);
}
