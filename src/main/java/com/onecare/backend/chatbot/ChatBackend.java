package com.onecare.backend.chatbot;

/** Whatever answers a chat query (the Django client in production, a fake in tests). Throws ChatbotUnavailableException when it cannot. */
public interface ChatBackend {
    ChatBackendResult query(String text);
}
