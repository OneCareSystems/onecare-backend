package com.onecare.backend.chatbot.knowledgebase;

class KnowledgeBaseRequestException extends RuntimeException {
    private final int status;
    KnowledgeBaseRequestException(int status, String message) {
        super(message);
        this.status = status;
    }
    int status() { return status; }
}
