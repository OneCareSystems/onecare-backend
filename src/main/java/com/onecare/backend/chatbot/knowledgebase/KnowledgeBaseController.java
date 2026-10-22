package com.onecare.backend.chatbot.knowledgebase;

import com.onecare.backend.chatbot.exception.InvalidInputException;
import com.onecare.backend.dto.ApiResponse;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chatbot/knowledge-base")
@PreAuthorize("hasAnyRole('DOCTOR','SUPER_ADMIN')")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping(path = "/links", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> submitLink(@RequestBody Map<String, Object> body) {
        if (!(body.get("url") instanceof String url)) {
            throw new InvalidInputException("A text URL is required.");
        }
        return ResponseEntity.accepted().body(
                new ApiResponse<>(true, "Knowledge base link submitted", service.submitLink(url)));
    }
}
