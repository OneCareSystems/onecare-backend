package com.onecare.backend.chatbot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApprovedUrlValidatorTest {
    private final ApprovedUrlValidator validator = new ApprovedUrlValidator(List.of("who.int", "nhs.uk"));

    private String message(String url) {
        return assertThrows(InvalidInputException.class, () -> validator.validate(url), String.valueOf(url)).getMessage();
    }

    @Test void an_approved_https_url_is_accepted_and_normalised() {
        assertEquals("https://who.int/", validator.validate("https://who.int"));
        assertEquals("https://www.who.int/page?x=1", validator.validate("https://www.who.int/page?x=1#section"));   // fragment dropped
        assertEquals("https://who.int/a/b", validator.validate("  https://WHO.INT:443/a/b  "));                        // case, default port, spaces
        assertEquals("https://who.int/x", validator.validate("https://who.int./x"));                                  // trailing dot
        assertEquals("https://a.b.nhs.uk/x", validator.validate("HTTPS://a.b.nhs.uk/x"));                              // deep subdomain, scheme case
    }

    @Test void the_path_and_query_are_kept_exactly() {
        assertEquals("https://who.int/fact-sheets/detail/dengue%20fever?lang=en&x=%E0%AE%A4",
                validator.validate("https://who.int/fact-sheets/detail/dengue%20fever?lang=en&x=%E0%AE%A4"));
    }

    @ParameterizedTest @ValueSource(strings = {"http://who.int", "ftp://who.int/x", "javascript:alert(1)", "file:///etc/passwd",
            "data:text/html,x", "who.int", "//who.int/x", "mailto:a@who.int", "gopher://who.int"})
    void only_https_is_accepted(String url) {
        assertTrue(message(url).equals("Only HTTPS links are accepted.") || message(url).equals("The URL has no host."), url);
    }

    @ParameterizedTest @ValueSource(strings = {"https://evil.com", "https://who.int.evil.com", "https://evilwho.int", "https://notwho.int",
            "https://who.int.evil.com/who.int", "https://evil.com/?u=who.int", "https://evil.com#who.int", "https://nhs.uk.evil.org",
            "https://who.intx", "https://xwho.int", "https://int", "https://wwho.int"})
    void hosts_outside_the_allowlist_are_rejected(String url) {
        assertEquals("This website is not on the approved list.", message(url));
    }

    @ParameterizedTest @ValueSource(strings = {"https://user:pass@who.int", "https://who.int@evil.com", "https://evil.com@who.int",
            "https://who.int:443@evil.com", "https://@who.int"})
    void credentials_in_the_url_are_rejected_including_the_at_sign_trick(String url) {
        assertEquals("Links with credentials are not accepted.", message(url));
    }

    @ParameterizedTest @ValueSource(strings = {"https://who.int:8443", "https://who.int:80", "https://who.int:abc", "https://who.int:", "https://who.int:443443"})
    void only_the_default_port_is_accepted(String url) {
        assertEquals("Only the default HTTPS port is accepted.", message(url));
    }

    @ParameterizedTest @ValueSource(strings = {"https://127.0.0.1", "https://10.0.0.1/x", "https://169.254.169.254/latest/meta-data", "https://2130706433",
            "https://0x7f.0.0.1", "https://127.1", "https://[::1]", "https://[2001:db8::1]/x", "https://192.168.1.1:443"})
    void ip_addresses_in_any_notation_are_rejected(String url) {
        assertEquals("IP addresses are not accepted; use an approved hostname.", message(url));
    }

    @Test void localhost_is_rejected_because_it_is_not_on_the_list() {
        assertEquals("This website is not on the approved list.", message("https://localhost"));
    }

    @Test void unicode_lookalike_hosts_become_punycode_and_do_not_match() {
        assertEquals("This website is not on the approved list.", message("https://wh\u043e.int"));     // Cyrillic o
        assertEquals("This website is not on the approved list.", message("https://who.\u0456nt"));     // Cyrillic i
    }

    @ParameterizedTest @ValueSource(strings = {"https://who.int\\@evil.com", "https://who.int/a b", "https://who.int/\npath", "https://who.int/\tx",
            "https://who%2eint", "https://who_int.int", "https://-who.int", "https://who..int", "https://who.int/<script>", "https://who.int/\u0000"})
    void malformed_or_dangerous_urls_are_rejected(String url) {
        String m = message(url);
        assertTrue(m.equals("The URL is not valid.") || m.equals("The URL contains invalid characters.") || m.equals("The URL host is not valid.")
                || m.equals("This website is not on the approved list."), url + " -> " + m);
    }

    @Test void control_characters_at_the_edges_are_rejected_not_silently_trimmed() {
        assertEquals("The URL contains invalid characters.", message("https://who.int/\u0000"));
        assertEquals("The URL contains invalid characters.", message("\u0000https://who.int/"));
        assertEquals("The URL contains invalid characters.", message("https://who.int/\u001F"));
        assertEquals("https://who.int/", validator.validate("\n https://who.int/ \t"));       // plain whitespace padding is fine
    }

    @Test void null_blank_and_oversize_are_rejected() {
        assertEquals("A valid URL is required.", message(null));
        assertEquals("A valid URL is required.", message(""));
        assertEquals("A valid URL is required.", message("   "));
        assertEquals("A valid URL is required.", message("https://who.int/" + "a".repeat(2100)));
    }

    @Test void an_overlong_host_label_is_rejected() {
        String m = message("https://" + "a".repeat(64) + ".who.int");
        assertEquals("The URL host is not valid.", m);
    }

    @Test void an_empty_allowlist_rejects_everything() {
        ApprovedUrlValidator none = new ApprovedUrlValidator(List.of());
        assertFalse(none.hasApprovedDomains());
        assertEquals("This website is not on the approved list.",
                assertThrows(InvalidInputException.class, () -> none.validate("https://who.int")).getMessage());
        assertFalse(new ApprovedUrlValidator(null).hasApprovedDomains());
    }

    @Test void allowlist_entries_are_normalised() {
        ApprovedUrlValidator v = new ApprovedUrlValidator(List.of(" WHO.INT ", "*.nhs.uk", ".cdc.gov", "moh.gov.lk.", "", "  "));
        assertDoesNotThrow(() -> v.validate("https://who.int"));
        assertDoesNotThrow(() -> v.validate("https://www.nhs.uk"));
        assertDoesNotThrow(() -> v.validate("https://www.cdc.gov"));
        assertDoesNotThrow(() -> v.validate("https://moh.gov.lk"));
    }

    @ParameterizedTest @ValueSource(strings = {"com", "lk", "http://who.int", "who.int/path", "who int", "who.int:443", "-who.int", "who..int", "https://who.int", "@who.int"})
    void a_bad_allowlist_entry_fails_at_startup(String entry) {
        assertThrows(IllegalArgumentException.class, () -> new ApprovedUrlValidator(List.of(entry)));
    }
}
