package com.onecare.backend.chatbot.knowledgebase;

import java.util.List;

public record KnowledgeDocumentPage(List<KnowledgeDocumentResponse> items, long count,
        int page, int pageSize, int totalPages) {}
