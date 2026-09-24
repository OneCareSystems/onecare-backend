package com.onecare.backend.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ApiRateLimitFilterTest {

    private static final String AUTH_PATH = "/api/auth/forgot-password";

    private ApiRateLimitFilter filter;
    private FilterChain chain;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ApiRateLimitFilter(true, 3, 60);
        chain = mock(FilterChain.class);
        response = new MockHttpServletResponse();
    }

    private MockHttpServletRequest request(String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(ip);
        return request;
    }

    @Test
    void shouldAllowRequestsWithinLimit() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request(AUTH_PATH, "10.0.0.1"), response, chain);
        }
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRejectRequestOverLimitWith429() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request(AUTH_PATH, "10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        filter.doFilter(request(AUTH_PATH, "10.0.0.1"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString())
                .contains("\"success\":false")
                .contains("Too many requests");
        verify(chain, times(3)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldTrackClientsIndependently() throws Exception {
        for (int i = 0; i < 4; i++) {
            filter.doFilter(request(AUTH_PATH, "10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        filter.doFilter(request(AUTH_PATH, "10.0.0.2"), response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(chain, times(4)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldIgnoreNonAuthPaths() throws Exception {
        for (int i = 0; i < 10; i++) {
            filter.doFilter(request("/api/patients", "10.0.0.1"), response, chain);
        }
        verify(chain, times(10)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldPassThroughWhenDisabled() throws Exception {
        ApiRateLimitFilter disabled = new ApiRateLimitFilter(false, 1, 60);
        for (int i = 0; i < 10; i++) {
            disabled.doFilter(request(AUTH_PATH, "10.0.0.1"), response, chain);
        }
        verify(chain, times(10)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldTrackBucketsPerEndpointIndependently() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request("/api/auth/forgot-password", "10.0.0.1"), new MockHttpServletResponse(), chain);
        }
        filter.doFilter(request("/api/auth/forgot-password", "10.0.0.1"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        // Same IP, different endpoint: its own bucket is untouched
        MockHttpServletResponse loginResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/auth/login", "10.0.0.1"), loginResponse, chain);
        assertThat(loginResponse.getStatus()).isEqualTo(200);

        verify(chain, times(4)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldResetCountAfterWindowElapses() throws Exception {
        ApiRateLimitFilter shortWindow = new ApiRateLimitFilter(true, 1, 1); // 1 req / 1s

        shortWindow.doFilter(request(AUTH_PATH, "10.0.0.1"), new MockHttpServletResponse(), chain);
        shortWindow.doFilter(request(AUTH_PATH, "10.0.0.1"), response, chain);
        assertThat(response.getStatus()).isEqualTo(429);

        Thread.sleep(1100); // previous window has expired

        MockHttpServletResponse afterWindow = new MockHttpServletResponse();
        shortWindow.doFilter(request(AUTH_PATH, "10.0.0.1"), afterWindow, chain);
        assertThat(afterWindow.getStatus()).isEqualTo(200);
        verify(chain, times(2)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldUseFirstForwardedForIpWhenPresent() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(request(AUTH_PATH, "10.0.0.1"), new MockHttpServletResponse(), chain);
        }

        MockHttpServletRequest overLimit = request(AUTH_PATH, "127.0.0.1");
        overLimit.addHeader("X-Forwarded-For", "10.0.0.1, 172.16.0.1");
        filter.doFilter(overLimit, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
    }
}
