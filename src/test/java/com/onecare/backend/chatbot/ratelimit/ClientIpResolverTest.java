package com.onecare.backend.chatbot.ratelimit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;


class ClientIpResolverTest {
    private final ClientIpResolver none = new ClientIpResolver(List.of());
    private final ClientIpResolver behindProxy = new ClientIpResolver(List.of("10.0.0.0/8", "::1", "fd00::/8", "192.168.1.5"));

    @Test void without_trusted_proxies_the_forwarded_header_is_never_used() {
        assertEquals("203.0.113.50", none.resolve("203.0.113.50", "1.1.1.1"));
        assertEquals("203.0.113.50", none.resolve("203.0.113.50", "9.9.9.9, 8.8.8.8"));
    }

    @Test void a_direct_client_cannot_spoof_the_header_even_when_a_proxy_list_exists() {
        assertEquals("203.0.113.50", behindProxy.resolve("203.0.113.50", "1.2.3.4"));
    }

    @Test void behind_a_trusted_proxy_the_forwarded_client_is_used() {
        assertEquals("203.0.113.9", behindProxy.resolve("10.0.0.5", "203.0.113.9"));
    }

    @Test void the_rightmost_untrusted_address_wins_so_a_spoofed_left_part_is_ignored() {
        assertEquals("203.0.113.9", behindProxy.resolve("10.0.0.5", "1.2.3.4, 203.0.113.9"));            // client sent 1.2.3.4, proxy added the real one
        assertEquals("203.0.113.9", behindProxy.resolve("10.0.0.5", "6.6.6.6, 1.2.3.4, 203.0.113.9"));
    }

    @Test void trusted_proxies_in_the_chain_are_skipped() {
        assertEquals("203.0.113.9", behindProxy.resolve("10.0.0.5", "203.0.113.9, 10.0.0.7, 192.168.1.5"));
    }

    @Test void junk_in_the_chain_means_the_header_is_ignored() {
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", "garbage"));
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", "203.0.113.9, not-an-ip"));
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", "999.1.1.1"));
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", "example.com"));          // hostnames are never resolved
    }

    @Test void missing_or_all_trusted_header_falls_back_to_the_peer() {
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", null));
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", "  "));
        assertEquals("10.0.0.5", behindProxy.resolve("10.0.0.5", "10.0.0.9, 10.0.0.8"));
    }

    @Test void ipv6_and_ipv4_mapped_addresses_are_handled() {
        assertEquals("2001:db8::7", behindProxy.resolve("::1", "2001:db8::7"));
        assertEquals("203.0.113.9", behindProxy.resolve("::ffff:10.0.0.5", "203.0.113.9"));        // mapped address matches 10.0.0.0/8
        assertEquals("203.0.113.9", behindProxy.resolve("fd00::1", "203.0.113.9"));
    }

    @Test void ports_brackets_and_zones_are_stripped() {
        assertEquals("203.0.113.9", behindProxy.resolve("10.0.0.5", "203.0.113.9:5555"));
        assertEquals("2001:db8::1", behindProxy.resolve("10.0.0.5", "[2001:db8::1]:443"));
        assertEquals("fe80::1", behindProxy.resolve("fe80::1%eth0", null));
    }

    @Test void a_missing_peer_address_is_unknown() {
        assertEquals("unknown", none.resolve(null, "1.1.1.1"));
        assertEquals("unknown", none.resolve("  ", null));
    }

    @Test void an_unparseable_peer_is_returned_as_is_and_never_looked_up() {
        assertEquals("not-an-ip", behindProxy.resolve("not-an-ip", "1.2.3.4"));
    }

    @Test void the_rate_limit_key_for_ipv4_is_the_address() {
        assertEquals("v4:cb007109", ClientIpResolver.rateLimitKey("203.0.113.9"));
        assertNotEquals(ClientIpResolver.rateLimitKey("203.0.113.9"), ClientIpResolver.rateLimitKey("203.0.113.10"));
    }

    @Test void ipv6_addresses_in_the_same_slash_64_share_one_key() {
        String a = ClientIpResolver.rateLimitKey("2001:db8:1:2::1");
        String b = ClientIpResolver.rateLimitKey("2001:db8:1:2:ffff:ffff:ffff:ffff");
        String other = ClientIpResolver.rateLimitKey("2001:db8:1:3::1");
        assertEquals(a, b);
        assertNotEquals(a, other);
        assertTrue(a.startsWith("v6:"));
    }

    @Test void a_mapped_ipv4_address_uses_the_ipv4_key() {
        assertEquals(ClientIpResolver.rateLimitKey("203.0.113.9"), ClientIpResolver.rateLimitKey("::ffff:203.0.113.9"));
    }

    @Test void odd_input_gives_a_stable_key_not_an_exception() {
        assertEquals("unknown", ClientIpResolver.rateLimitKey(null));
        assertEquals("unknown", ClientIpResolver.rateLimitKey("  "));
        assertEquals("weird", ClientIpResolver.rateLimitKey("weird"));
    }

    @Test void bad_trusted_proxy_entries_fail_at_startup() {
        for (String bad : new String[] {"abc", "10.0.0.0/33", "10.0.0.0/x", "::1/129", "999.0.0.1", "10.0.0"}) {
            assertThrows(IllegalArgumentException.class, () -> new ClientIpResolver(List.of(bad)), bad);
        }
    }

    @Test void cidr_prefixes_that_are_not_on_a_byte_boundary_work() {
        ClientIpResolver r = new ClientIpResolver(List.of("172.16.0.0/12"));
        assertEquals("203.0.113.9", r.resolve("172.31.255.254", "203.0.113.9"));       // inside /12
        assertEquals("172.32.0.1", r.resolve("172.32.0.1", "203.0.113.9"));            // just outside: header ignored
    }
}
