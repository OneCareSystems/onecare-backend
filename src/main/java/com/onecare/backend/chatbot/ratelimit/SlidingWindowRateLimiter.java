package com.onecare.backend.chatbot.ratelimit;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongSupplier;

public final class SlidingWindowRateLimiter {
    public record Decision(boolean allowed, int limit, int remaining, long retryAfterSeconds) {}

    private final int limit;
    private final long windowNanos;
    private final int maxKeys;
    private final LongSupplier clock;
    private final Map<String, ArrayDeque<Long>> hits = new HashMap<>();
    private long lastPurge;
    private long overflow;

    public SlidingWindowRateLimiter(int limit, Duration window, int maxKeys, LongSupplier nanoClock) {
        if (limit < 1) throw new IllegalArgumentException("limit must be at least 1");
        if (window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be positive");
        if (maxKeys < 1) throw new IllegalArgumentException("maxKeys must be at least 1");
        this.limit = limit;
        this.windowNanos = window.toNanos();
        this.maxKeys = maxKeys;
        this.clock = nanoClock;
        this.lastPurge = nanoClock.getAsLong();
    }

    public synchronized Decision tryAcquire(String key) {
        long now = clock.getAsLong();
        if (now - lastPurge >= windowNanos) {
            purge(now);
            lastPurge = now;
        }
        ArrayDeque<Long> queue = hits.get(key);
        if (queue == null) {
            if (hits.size() >= maxKeys) {
                purge(now);
                if (hits.size() >= maxKeys) {
                    overflow++;
                    return new Decision(true, limit, limit - 1, 0);          // table full: do not punish the newcomer
                }
            }
            queue = new ArrayDeque<>();
            hits.put(key, queue);
        }
        evict(queue, now);
        if (queue.size() >= limit) {
            long waitNanos = queue.peekFirst() + windowNanos - now;
            long seconds = Math.max(1, (waitNanos + 999_999_999L) / 1_000_000_000L);
            return new Decision(false, limit, 0, seconds);
        }
        queue.addLast(now);
        return new Decision(true, limit, limit - queue.size(), 0);
    }

    private void evict(ArrayDeque<Long> queue, long now) {
        while (!queue.isEmpty() && now - queue.peekFirst() >= windowNanos) queue.pollFirst();
    }

    private void purge(long now) {
        Iterator<Map.Entry<String, ArrayDeque<Long>>> it = hits.entrySet().iterator();
        while (it.hasNext()) {
            ArrayDeque<Long> queue = it.next().getValue();
            evict(queue, now);
            if (queue.isEmpty()) it.remove();
        }
    }

    public synchronized int trackedClients() { return hits.size(); }

    public synchronized long overflowCount() { return overflow; }

    /** For tests. */
    public synchronized void clear() {
        hits.clear();
        overflow = 0;
    }
}
