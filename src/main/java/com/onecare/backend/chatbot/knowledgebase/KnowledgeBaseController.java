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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MultipartFile;
import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;


@RestController
@RequestMapping("/api/chatbot/knowledge-base")
@PreAuthorize("hasAnyRole('DOCTOR','SUPER_ADMIN')")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }


    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> uploadPdf(
            @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "") String title) {
        return ResponseEntity.status(201).body(new ApiResponse<>(true, "PDF queued for ingestion", service.uploadPdf(file, title)));
    }

    @GetMapping
    public ApiResponse<KnowledgeDocumentPage> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status) {
        return new ApiResponse<>(true, "Knowledge base resources retrieved", service.list(page, pageSize, sourceType, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocumentResponse> get(@PathVariable long id) {
        return new ApiResponse<>(true, "Knowledge base resource retrieved", service.get(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable long id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
    }


    @GetMapping(value = "/{id}/file", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> file(@PathVariable long id) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"document-" + id + ".pdf\"")
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(service.file(id));
    }

    @ExceptionHandler(KnowledgeBaseRequestException.class)
    public ResponseEntity<ApiResponse<Void>> requestError(KnowledgeBaseRequestException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiResponse<>(false, exception.getMessage()));
    }

    @ExceptionHandler(ChatbotUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable(ChatbotUnavailableException exception) {
        return ResponseEntity.status(503).body(new ApiResponse<>(false, "Knowledge base service is unavailable. Please try again."));
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> missingFile(org.springframework.web.multipart.support.MissingServletRequestPartException exception) {
        return ResponseEntity.badRequest().body(new ApiResponse<>(false, "A PDF file is required."));
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
