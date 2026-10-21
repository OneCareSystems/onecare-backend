package com.onecare.backend.chatbot.ratelimit;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;


class SlidingWindowRateLimiterTest {
    private static final long SECOND = 1_000_000_000L;
    private final AtomicLong now = new AtomicLong(1_000 * SECOND);
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(20, Duration.ofMinutes(1), 1000, now::get);

    @Test void twenty_requests_are_allowed_and_the_21st_is_refused() {
        for (int i = 1; i <= 20; i++) {
            SlidingWindowRateLimiter.Decision d = limiter.tryAcquire("a");
            assertTrue(d.allowed(), "request " + i);
            assertEquals(20 - i, d.remaining());
            assertEquals(20, d.limit());
        }
        SlidingWindowRateLimiter.Decision refused = limiter.tryAcquire("a");
        assertFalse(refused.allowed());
        assertEquals(0, refused.remaining());
        assertTrue(refused.retryAfterSeconds() >= 1 && refused.retryAfterSeconds() <= 60);
    }

    @Test void retry_after_tells_how_long_until_the_oldest_request_leaves_the_window() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("a");
        now.addAndGet(10 * SECOND);
        assertEquals(50, limiter.tryAcquire("a").retryAfterSeconds());
        now.addAndGet(49 * SECOND + SECOND / 2);
        assertEquals(1, limiter.tryAcquire("a").retryAfterSeconds());           // rounds up, never 0
    }

    @Test void the_window_slides_requests_free_up_one_by_one() {
        for (int i = 0; i < 10; i++) { limiter.tryAcquire("a"); now.addAndGet(SECOND); }          // 10 requests, 1 s apart
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquire("a").allowed());               // 10 more at t=10 s
        assertFalse(limiter.tryAcquire("a").allowed());                                            // 20 in the last minute
        now.addAndGet(59 * SECOND + 1);                                                            // t=69 s: the first 10 (t=0..9 s) are older than 60 s
        for (int i = 0; i < 10; i++) assertTrue(limiter.tryAcquire("a").allowed(), "slot " + i);
        assertFalse(limiter.tryAcquire("a").allowed());
    }

    @Test void a_full_minute_later_everything_is_allowed_again() {
        for (int i = 0; i < 21; i++) limiter.tryAcquire("a");
        now.addAndGet(60 * SECOND);
        for (int i = 0; i < 20; i++) assertTrue(limiter.tryAcquire("a").allowed());
    }

    @Test void clients_are_counted_separately() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("a");
        assertFalse(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("b").allowed());
    }

    @Test void refused_requests_do_not_extend_the_block() {
        for (int i = 0; i < 20; i++) limiter.tryAcquire("a");
        for (int i = 0; i < 100; i++) { now.addAndGet(SECOND / 2); limiter.tryAcquire("a"); }     // hammering for 50 s
        now.set(1_000 * SECOND + 60 * SECOND);
        assertTrue(limiter.tryAcquire("a").allowed());                                             // still free at the 60 s mark
    }

    @Test void memory_is_bounded_and_overflow_clients_are_let_through_untracked() {
        SlidingWindowRateLimiter small = new SlidingWindowRateLimiter(2, Duration.ofMinutes(1), 3, now::get);
        for (String k : new String[] {"a", "b", "c"}) assertTrue(small.tryAcquire(k).allowed());
        assertEquals(3, small.trackedClients());
        assertTrue(small.tryAcquire("d").allowed());                // the table is full: not blocked, not tracked
        assertEquals(3, small.trackedClients());
        assertEquals(1, small.overflowCount());
        now.addAndGet(61 * SECOND);                                  // everyone idle: purged
        assertTrue(small.tryAcquire("d").allowed());
        assertEquals(1, small.trackedClients());
    }

    @Test void idle_clients_are_purged() {
        for (int i = 0; i < 50; i++) limiter.tryAcquire("c" + i);
        assertEquals(50, limiter.trackedClients());
        now.addAndGet(61 * SECOND);
        limiter.tryAcquire("fresh");
        assertEquals(1, limiter.trackedClients());
    }

    @Test void exactly_the_limit_is_allowed_under_heavy_concurrency() throws Exception {
        SlidingWindowRateLimiter real = new SlidingWindowRateLimiter(20, Duration.ofMinutes(1), 1000, System::nanoTime);
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        for (int i = 0; i < 500; i++) {
            pool.submit(() -> {
                try { start.await(); } catch (InterruptedException e) { return; }
                if (real.tryAcquire("same-client").allowed()) allowed.incrementAndGet();
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(20, allowed.get());
    }

    @Test void clear_resets_everything() {
        for (int i = 0; i < 21; i++) limiter.tryAcquire("a");
        limiter.clear();
        assertTrue(limiter.tryAcquire("a").allowed());
        assertEquals(1, limiter.trackedClients());
    }

    @Test void bad_arguments_are_rejected() {
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(0, Duration.ofMinutes(1), 10, now::get));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(1, Duration.ZERO, 10, now::get));
        assertThrows(IllegalArgumentException.class, () -> new SlidingWindowRateLimiter(1, Duration.ofMinutes(1), 0, now::get));
    }
}
