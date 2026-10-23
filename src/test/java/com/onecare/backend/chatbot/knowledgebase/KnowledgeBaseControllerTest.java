package com.onecare.backend.chatbot.knowledgebase;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.onecare.backend.chatbot.exception.ChatbotUnavailableException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class KnowledgeBaseControllerTest {
    private final KnowledgeBaseService service = mock(KnowledgeBaseService.class);
    private MockMvc mvc;
    private static final String BASE = "/api/chatbot/knowledge-base";

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeBaseController(service))
                .setControllerAdvice(new com.onecare.backend.exception.GlobalExceptionHandler()).build();
    }

    @Test void uploads_pdf_and_preserves_link_submission_contract() throws Exception {
        when(service.uploadPdf(any(), eq("Guide"))).thenReturn(new KnowledgeDocumentResponse(2L, "PENDING"));
        mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "guide.pdf", "application/pdf", new byte[]{1})).param("title", "Guide"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(2));
        when(service.submitLink("https://who.int/page")).thenReturn(new KnowledgeDocumentResponse(3L, "PENDING"));
        mvc.perform(post(BASE + "/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://who.int/page\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.id").value(3));
    }

    @Test void serves_pdf_bytes_and_reports_missing_files() throws Exception {
        byte[] pdf = "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(service.file(1)).thenReturn(pdf);
        mvc.perform(get(BASE + "/1/file")).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(content().bytes(pdf));
        when(service.file(2)).thenThrow(new KnowledgeBaseRequestException(404, "PDF not found."));
        mvc.perform(get(BASE + "/2/file")).andExpect(status().isNotFound());
    }

    @Test void lists_details_and_deletes_with_existing_api_envelope() throws Exception {
        when(service.list(2, 10, "PDF", "INDEXED"))
                .thenReturn(new KnowledgeDocumentPage(List.of(new KnowledgeDocumentResponse(1L, "INDEXED")), 11, 2, 10, 2));
        mvc.perform(get(BASE).param("page", "2").param("pageSize", "10").param("sourceType", "PDF").param("status", "INDEXED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.count").value(11));
        when(service.get(1)).thenReturn(new KnowledgeDocumentResponse(1L, "INDEXED"));
        mvc.perform(get(BASE + "/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("INDEXED"));
        mvc.perform(delete(BASE + "/1")).andExpect(status().isNoContent());
        verify(service).remove(1);
    }

    @Test void translates_django_validation_not_found_and_unavailable_errors() throws Exception {
        when(service.get(404)).thenThrow(new KnowledgeBaseRequestException(404, "Not found"));
        mvc.perform(get(BASE + "/404")).andExpect(status().isNotFound());
        when(service.get(500)).thenThrow(new ChatbotUnavailableException("Internal credential problem"));
        mvc.perform(get(BASE + "/500")).andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("credential"))));
        when(service.submitLink(anyString())).thenThrow(new KnowledgeBaseRequestException(400, "Unsafe URL"));
        mvc.perform(post(BASE + "/links").contentType(MediaType.APPLICATION_JSON).content("{\"url\":\"https://who.int\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Unsafe URL"));
    }

    @Test void missing_and_oversized_uploads_return_client_errors() throws Exception {
        mvc.perform(multipart(BASE)).andExpect(status().isBadRequest());
        when(service.uploadPdf(any(), anyString())).thenThrow(new org.springframework.web.multipart.MaxUploadSizeExceededException(10));
        mvc.perform(multipart(BASE).file(new MockMultipartFile("file", "guide.pdf", "application/pdf", new byte[]{1})))
                .andExpect(status().isPayloadTooLarge());
    }
}
