package com.onecare.backend.chatbot;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Creates the Stage 5 beans. The classes themselves are plain Java so they can be unit-tested without Spring. */
@Configuration
@EnableConfigurationProperties({ChatApiProperties.class, ChatbotProperties.class})
public class ChatApiConfig {

    @Bean
    public QueryInputValidator queryInputValidator(ChatApiProperties props) {
        return new QueryInputValidator(props.maxQueryChars());
    }

    @Bean
    public ApprovedUrlValidator approvedUrlValidator(ChatApiProperties props) {
        return new ApprovedUrlValidator(props.urlAllowlistDomains());        // a bad entry stops the application at startup
    }

    @Bean
    public SlidingWindowRateLimiter publicRateLimiter(ChatApiProperties props) {
        return new SlidingWindowRateLimiter(props.rateLimitPerMinute(), Duration.ofMinutes(1), props.maxTrackedClients(), System::nanoTime);
    }

    @Bean
    public ClientIpResolver clientIpResolver(ChatApiProperties props) {
        return new ClientIpResolver(props.trustedProxyList());
    }

    @Bean
    public ChatQueryService chatQueryService(QueryInputValidator validator, ChatBackend backend) {
        return new ChatQueryService(validator, backend);
    }
}
