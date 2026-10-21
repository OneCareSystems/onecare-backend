package com.onecare.backend.chatbot;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.onecare.backend.security.AppUserDetailsService.AppUserDetails;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Invalid URLs must be rejected before the audited submission bean or Django is called. */
class KnowledgeBaseLinkAllowlistTest {
    private final KnowledgeBaseClient chatbot = mock(KnowledgeBaseClient.class);
    private final KnowledgeBaseSubmission submission = mock(KnowledgeBaseSubmission.class);
    private KnowledgeBaseService service;

    @BeforeEach void setUp() {
        SecurityContextHolder.clearContext();
        service = new KnowledgeBaseService(chatbot, new ApprovedUrlValidator(List.of("who.int")), submission);
        var authorities = List.of(new SimpleGrantedAuthority("ROLE_DOCTOR"));
        var principal = new AppUserDetails(42L, "doctor", "unused", true, true, true, true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @AfterEach void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest @ValueSource(strings = {"https://evil.com", "http://www.who.int/page", "https://who.int.evil.com", "https://user:pw@who.int",
            "https://127.0.0.1/", "https://who.int:8443/", "javascript:alert(1)", "https://wh\u043e.int/"})
    void a_url_that_fails_the_allowlist_is_rejected_before_the_audit_row_and_before_django(String url) {
        assertThrows(InvalidInputException.class, () -> service.submitLink(url));
        verifyNoInteractions(submission, chatbot);
    }

    @Test void an_approved_url_is_submitted_in_its_normalised_form() {
        service.submitLink("  HTTPS://WWW.WHO.INT:443/page#top ");
        verify(submission).submitApprovedLink("https://www.who.int/page", 42L);
    }

    @Test void the_submission_bean_forwards_the_approved_url_and_actor_to_django() {
        new KnowledgeBaseSubmission(chatbot).submitApprovedLink("https://www.who.int/page", 42L);
        verify(chatbot).submitLink("https://www.who.int/page", 42L);
    }
}
