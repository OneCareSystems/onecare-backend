package com.onecare.backend.chatbot.ratelimit;

package com.onecare.backend.chatbot.ratelimit;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.onecare.backend.chatbot.config.ChatApiProperties;

/** Attaches the rate limit to the public endpoint only. The knowledge-base endpoints need a JWT and are not rate limited here. */
@Configuration
public class PublicEndpointWebConfig implements WebMvcConfigurer {
/**
 * Attaches the rate limit to the public endpoint only.
 * The knowledge-base endpoints need a JWT and are not rate limited here.
 */
@Configuration
public class PublicEndpointWebConfig implements WebMvcConfigurer {

    private final SlidingWindowRateLimiter limiter;
    private final ClientIpResolver resolver;
    private final ChatApiProperties props;

    public PublicEndpointWebConfig(SlidingWindowRateLimiter limiter, ClientIpResolver resolver, ChatApiProperties props) {
    public PublicEndpointWebConfig(
            SlidingWindowRateLimiter limiter,
            ClientIpResolver resolver,
            ChatApiProperties props) {
        this.limiter = limiter;
        this.resolver = resolver;
        this.props = props;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicRateLimitInterceptor(limiter, resolver, props.maxBodyBytes()))
                .addPathPatterns("/api/chatbot/query");
        registry.addInterceptor(
                new PublicRateLimitInterceptor(
                        limiter,
                        resolver,
                        props.getMaxBodyBytes()
                )
        ).addPathPatterns("/api/chatbot/query");
    }
}
