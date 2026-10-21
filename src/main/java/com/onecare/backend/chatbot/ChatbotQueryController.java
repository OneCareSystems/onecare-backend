package com.onecare.backend.chatbot;

import com.onecare.backend.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** API-01: the PUBLIC chatbot endpoint. No login and no JWT (FR-043), so there is deliberately no @PreAuthorize here. */
@RestController
@RequestMapping("/api/chatbot")
@Tag(name = "Chatbot", description = "Public: no login required. Rate limited to 20 requests per minute per IP.")
public class ChatbotQueryController {
    private final ChatQueryService service;

    public ChatbotQueryController(ChatQueryService service) { this.service = service; }

    @Operation(summary = "Ask the chatbot a question (English or Tamil)",
            description = "JSON {\"query\": \"...\"}, UTF-8, at most 500 characters. Returns type ANSWER, REFUSAL, FALLBACK or CLINIC_FACT, "
                    + "the language (EN or TA), the message and, for an ANSWER, the cited sources. No login needed.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "A reply of any type. A chatbot outage is also a 200 FALLBACK.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing, empty, non-text, too long, or invalid characters")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415", description = "Content-Type is not application/json")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "More than 20 requests in a minute from this client. See the Retry-After header.")
    @PostMapping(path = "/query", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<ChatQueryResponse> query(@RequestBody Map<String, Object> body) {
        return new ApiResponse<>(true, "Chatbot query processed successfully", service.answer(body.get("query")));
    }
}
