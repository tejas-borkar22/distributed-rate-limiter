package com.ratelimiter.core.strategy;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTokenBucketStrategyTest {
    @Test
    void allowsRequestsUpToCapacityThenDenies() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        Clock clock = Clock.fixed(start, ZoneOffset.UTC);
        InMemoryTokenBucketStrategy strategy = new InMemoryTokenBucketStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(3, 60); // 3 requests per 60s

        // Bucket starts full: 3 tokens
        assertTrue(strategy.tryConsume("client-a", config).allowed());
        assertTrue(strategy.tryConsume("client-a", config).allowed());
        assertTrue(strategy.tryConsume("client-a", config).allowed());

        // 4th request in the same instant: bucket is empty
        RateLimitResult result = strategy.tryConsume("client-a", config);
        assertFalse(result.allowed());
        assertEquals(0, result.remainingTokens());
    }

    @Test
    void refillsTokensOverTime() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryTokenBucketStrategy strategy = new InMemoryTokenBucketStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(6, 60); // 6 per 60s = 1 token every 10s

        // Drain the bucket completely
        for (int i = 0; i < 6; i++) {
            strategy.tryConsume("client-b", config);
        }
        assertFalse(strategy.tryConsume("client-b", config).allowed());

        // Advance 10 simulated seconds, exactly one token should refill
        clock.advanceSeconds(10);
        assertTrue(strategy.tryConsume("client-b", config).allowed());
    }

    @Test
    void differentClientsHaveIndependentBuckets() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InMemoryTokenBucketStrategy strategy = new InMemoryTokenBucketStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(1, 60);

        assertTrue(strategy.tryConsume("client-x", config).allowed());
        assertFalse(strategy.tryConsume("client-x", config).allowed()); // client-x exhausted

        // client-y is unaffected by client-x's usage
        assertTrue(strategy.tryConsume("client-y", config).allowed());
    }

    /** Simple mutable Clock for simulating elapsed time in tests without real sleeps. */
    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}