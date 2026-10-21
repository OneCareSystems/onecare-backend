package com.onecare.backend.chatbot;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.onecare.backend.security.AppUserDetailsService.AppUserDetails;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Role checks for the implemented knowledge-base link service. */
class KnowledgeBaseRbacTest {
    private static final String URL = "https://www.who.int/page";
    private final KnowledgeBaseClient chatbot = mock(KnowledgeBaseClient.class);
    private final KnowledgeBaseSubmission submission = mock(KnowledgeBaseSubmission.class);
    private KnowledgeBaseService service;

    @BeforeEach void setUp() {
        SecurityContextHolder.clearContext();
        service = new KnowledgeBaseService(chatbot, new ApprovedUrlValidator(List.of("who.int")), submission);
    }

    @AfterEach void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String role) {
        List<SimpleGrantedAuthority> authorities = role == null ? List.of()
                : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        var principal = new AppUserDetails(42L, "u1", "unused", true, true, true, true, authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    @ParameterizedTest @ValueSource(strings = {"DOCTOR", "SUPER_ADMIN"})
    void doctor_and_super_admin_can_submit_links(String role) {
        authenticate(role);
        var response = new KnowledgeDocumentResponse(1L, "PENDING");
        when(submission.submitApprovedLink(URL, 42L)).thenReturn(response);

        assertSame(response, service.submitLink(URL));
        verify(submission).submitApprovedLink(URL, 42L);
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "PHARMACIST", "PATIENT", "NURSE", "RECEPTIONIST", "USER", "GUEST"})
    void every_other_role_is_denied_before_submission(String role) {
        authenticate(role);
        assertDenied();
    }

    @Test void a_user_with_no_role_is_denied() {
        authenticate(null);
        assertDenied();
    }

    @Test void a_request_without_authentication_is_denied() {
        assertDenied();
    }

    @Test void an_anonymous_principal_is_denied() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "test-key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertDenied();
    }

    private void assertDenied() {
        assertThrows(AccessDeniedException.class, () -> service.submitLink(URL));
        verifyNoInteractions(submission, chatbot);
    }
}
