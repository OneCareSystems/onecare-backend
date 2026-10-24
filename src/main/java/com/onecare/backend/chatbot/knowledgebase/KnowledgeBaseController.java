package com.onecare.backend.chatbot.knowledgebase;

import com.onecare.backend.chatbot.exception.InvalidInputException;
import com.onecare.backend.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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


/**
 * Chatbot knowledge-base management (upload PDFs / submit links / manage
 * documents). Delegates storage and indexing to the Django knowledge-base
 * service; its validation errors (400/404/409/413/415/422) are passed through
 * with a sanitized message, and any outage surfaces as 503.
 */
@Tag(name = "Chatbot Knowledge Base",
        description = "Manage the chatbot's knowledge base: upload PDFs, submit approved links, "
                + "list and delete documents. Requires a login token with the DOCTOR or "
                + "SUPER_ADMIN role. Writes are audited (module CHATBOT).")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/chatbot/knowledge-base")
@PreAuthorize("hasAnyRole('DOCTOR','SUPER_ADMIN')")
public class KnowledgeBaseController {
    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }


    @Operation(summary = "Upload a PDF to the knowledge base",
            description = "multipart/form-data with a PDF 'file' and an optional 'title' "
                    + "(defaults to the filename downstream). Queued for ingestion — the "
                    + "returned status reflects the ingestion state (QUEUED/INDEXED/...). "
                    + "PDF content and size validation is done by the knowledge-base service.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
            description = "PDF accepted and queued for ingestion")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Missing or empty file, or the knowledge-base service rejected the document (e.g. not a PDF, too large)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
            description = "Request is not multipart/form-data")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "Knowledge-base service unavailable or timed out")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> uploadPdf(
            @RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "") String title) {
        return ResponseEntity.status(201).body(new ApiResponse<>(true, "PDF queued for ingestion", service.uploadPdf(file, title)));
    }

    @Operation(summary = "List knowledge-base documents",
            description = "Paginated listing, 1-based. Optional 'sourceType' (PDF/LINK) and "
                    + "'status' (e.g. QUEUED/INDEXED/FAILED) filters. pageSize max 100. "
                    + "Use 'count' for the total number of matching documents.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Page of documents in data.items, totals in data.count/data.totalPages")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Invalid pagination (page < 1, pageSize < 1 or > 100)")
    @GetMapping
    public ApiResponse<KnowledgeDocumentPage> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status) {
        return new ApiResponse<>(true, "Knowledge base resources retrieved", service.list(page, pageSize, sourceType, status));
    }

    @Operation(summary = "Get one knowledge-base document",
            description = "Returns ingestion metadata: status, title, source, language, "
                    + "needsReview, failureReason and who uploaded it.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Document found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "id must be >= 1")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Document not found in the knowledge base")
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeDocumentResponse> get(@PathVariable long id) {
        return new ApiResponse<>(true, "Knowledge base resource retrieved", service.get(id));
    }

    @Operation(summary = "Delete a knowledge-base document",
            description = "Permanently removes the document from the knowledge base "
                    + "(chunks and vectors included). Audited as KB_RESOURCE_REMOVED.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204",
            description = "Deleted (no content)")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "id must be >= 1")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404",
            description = "Document not found in the knowledge base")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(@PathVariable long id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
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


    @Operation(summary = "Submit an approved web link for ingestion",
            description = "JSON {\"url\": \"https://...\"}. The URL must be HTTPS on a domain "
                    + "in the approved allowlist (see ApprovedUrlValidator); rejected URLs are "
                    + "400 with the validation message. Accepted with 202 — indexing runs "
                    + "asynchronously in the knowledge-base service.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202",
            description = "Link accepted for ingestion")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400",
            description = "Missing/non-text 'url', or the URL is not an approved HTTPS domain")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "415",
            description = "Content-Type is not application/json")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503",
            description = "Knowledge-base service unavailable or timed out")
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
