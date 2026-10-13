package com.onecare.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Configuration
public class TestRedisConfiguration {

    private record ExpirableEntry(String value, long expireAtMillis) {
        boolean isExpired() {
            return System.currentTimeMillis() >= expireAtMillis;
        }
    }

    private final Map<String, ExpirableEntry> inMemoryStore = new ConcurrentHashMap<>();

    @Bean
    @Primary
    public RedisConnectionFactory redisConnectionFactory() {
        return mock(RedisConnectionFactory.class);
    }

    @Bean
    @Primary
    @SuppressWarnings("unchecked")
    public StringRedisTemplate stringRedisTemplate() {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

        when(template.opsForValue()).thenReturn(valueOperations);

        doAnswer(invocation -> {
            String key = invocation.getArgument(0);
            String value = invocation.getArgument(1);
            long timeout = invocation.getArgument(2);
            TimeUnit unit = invocation.getArgument(3);
            long expireAt = System.currentTimeMillis() + unit.toMillis(timeout);
            inMemoryStore.put(key, new ExpirableEntry(value, expireAt));
            return null;
        }).when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        when(valueOperations.get(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            ExpirableEntry entry = inMemoryStore.get(key);
            if (entry == null) {
                return null;
            }
            if (entry.isExpired()) {
                inMemoryStore.remove(key);
                return null;
            }
            return entry.value();
        });

        when(template.hasKey(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            ExpirableEntry entry = inMemoryStore.get(key);
            if (entry == null) {
                return false;
            }
            if (entry.isExpired()) {
                inMemoryStore.remove(key);
                return false;
            }
            return true;
        });

        when(template.getExpire(anyString(), any(TimeUnit.class))).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            TimeUnit unit = invocation.getArgument(1);
            ExpirableEntry entry = inMemoryStore.get(key);
            if (entry == null || entry.isExpired()) {
                inMemoryStore.remove(key);
                return -2L;
            }
            long remainingMillis = entry.expireAtMillis() - System.currentTimeMillis();
            return unit.convert(remainingMillis, TimeUnit.MILLISECONDS);
        });

        when(template.delete(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            return inMemoryStore.remove(key) != null;
        });

        return template;
    }
}
