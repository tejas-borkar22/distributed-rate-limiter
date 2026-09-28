package com.ratelimiter.core.strategy;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class InMemorySlidingWindowStrategyTest {

    // Fixed starting point. Every test moves time forward from here,
    // so "t=5" in a comment means START + 5 seconds.
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    // allowsRequestsUpToCapacityThenDenies
    // capacity 3, all requests at the same instant, the first 3 are allowed, the 4th is denied.
    // Extra check : on the 4th, what should remainingTokens be?
    @Test
    void allowsRequestsUpToCapacityThenDenies(){
        MutableClock clock = new MutableClock(START);
        InMemorySlidingWindowStrategy strategy = new InMemorySlidingWindowStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(3, 10); // 3 requests per 10 seconds

        // t=0: fill the window completely
        assertTrue(strategy.tryConsume("client-a", config).allowed());
        assertTrue(strategy.tryConsume("client-a", config).allowed());
        assertTrue(strategy.tryConsume("client-a", config).allowed());

        // 4th request in the same instant remaining tokens are 0
        RateLimitResult result = strategy.tryConsume("client-a",config);
        assertFalse(result.allowed());
        assertEquals(0, result.remainingTokens());
    }

    // oldRequestsExpireAndFreeUpCapacity
    @Test
    void oldRequestsExpireAndFreeUpCapacity() {
        MutableClock clock = new MutableClock(START);
        InMemorySlidingWindowStrategy strategy = new InMemorySlidingWindowStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(3, 10); // 3 requests per 10 seconds

        // t=0: fill the window completely
        for (int i = 0; i < 3; i++) {
            assertTrue(strategy.tryConsume("client-a", config).allowed());
        }

        // t=5: still full, so denied. The oldest entry is t=0, which expires at t=10.
        clock.advanceSeconds(5);
        RateLimitResult denied = strategy.tryConsume("client-a", config);
        assertFalse(denied.allowed());
        assertEquals(START.plusSeconds(10), denied.resetAt());
        assertEquals(5, denied.retryAfterSeconds()); // t=10 minus t=5

        // t=11: the three t=0 entries are now older than 10s, so they are all evicted
        clock.advanceSeconds(6);
        RateLimitResult allowed = strategy.tryConsume("client-a", config);
        assertTrue(allowed.allowed());
        assertEquals(2, allowed.remainingTokens()); // 3 capacity minus this 1 request
    }

    // deniedRequestsAreNotRecorded
    @Test
    void deniedRequestsAreNotRecorded() {
        MutableClock clock = new MutableClock(START);
        InMemorySlidingWindowStrategy strategy = new InMemorySlidingWindowStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(2, 10);

        // t=0: use both slots
        strategy.tryConsume("client-a", config);
        strategy.tryConsume("client-a", config);

        // t=5: hammer the API with 3 attempts, all should be denied
        clock.advanceSeconds(5);
        for (int i = 0; i < 3; i++) {
            assertFalse(strategy.tryConsume("client-a", config).allowed());
        }

        // t=11: the two t=0 entries have expired. If the denied attempts at t=5
        // had been logged, the log would still hold 3 entries and this would fail.
        clock.advanceSeconds(6);
        RateLimitResult result = strategy.tryConsume("client-a", config);
        assertTrue(result.allowed());
        assertEquals(1, result.remainingTokens());
    }

    // entriesExpireOneAtATime
    // The signature sliding-window behavior. Timeline is in a comment below.
    @Test
    void entriesExpireOneAtATime() {
        MutableClock clock = new MutableClock(START);
        InMemorySlidingWindowStrategy strategy = new InMemorySlidingWindowStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(2, 10); // 2 requests per 10 seconds

        // t=4: first request, log: [4]
        clock.advanceSeconds(4);
        RateLimitResult r4 = strategy.tryConsume("client-a", config);
        assertTrue(r4.allowed());
        assertEquals(1, r4.remainingTokens());

        // t=8: second request, log: [4, 8], window is now full
        clock.advanceSeconds(4);
        RateLimitResult r8 = strategy.tryConsume("client-a", config);
        assertTrue(r8.allowed());
        assertEquals(0, r8.remainingTokens());                       // FIXED: was 1

        // t=9: denied. Oldest entry is t=4, which expires at t=14
        clock.advanceSeconds(1);
        RateLimitResult r9 = strategy.tryConsume("client-a", config);
        assertFalse(r9.allowed());
        assertEquals(0, r9.remainingTokens());
        assertEquals(5, r9.retryAfterSeconds());                     // FIXED: was 1 (t=14 minus t=9)
        assertEquals(START.plusSeconds(14), r9.resetAt());           // FIXED: was START + 10

        // t=15: only the t=4 entry has expired; t=8 is still inside. log: [8, 15]
        clock.advanceSeconds(6);
        RateLimitResult r15 = strategy.tryConsume("client-a", config);
        assertTrue(r15.allowed());
        assertEquals(0, r15.remainingTokens());

        // t=16: log [8, 15] is full again. Oldest is t=8, which expires at t=18
        clock.advanceSeconds(1);
        RateLimitResult r16 = strategy.tryConsume("client-a", config);
        assertFalse(r16.allowed());
        assertEquals(2, r16.retryAfterSeconds());
        assertEquals(START.plusSeconds(18), r16.resetAt());

        // t=19: only the t=8 entry has expired; t=15 is still inside. log: [15, 19]
        clock.advanceSeconds(3);
        RateLimitResult r19 = strategy.tryConsume("client-a", config);
        assertTrue(r19.allowed());
        assertEquals(0, r19.remainingTokens());
    }

    // differentClientsHaveIndependentLogs
    // Capacity 1. client-x uses its slot and gets denied. client-y must be allowed.
    @Test
    void differentClientsHaveIndependentLogs(){
        MutableClock clock = new MutableClock(START);
        InMemorySlidingWindowStrategy strategy = new InMemorySlidingWindowStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(1, 10); // 1 requests per 10 seconds

        // client-x
        assertTrue(strategy.tryConsume("client-x",config).allowed());
        assertFalse(strategy.tryConsume("client-x",config).allowed());

        // client-y : allowed
        assertTrue(strategy.tryConsume("client-y",config).allowed());
    }

    // requestExactlyOnWindowEdge
    // Boundary test.
    @Test
    void requestExactlyOnWindowEdge(){
        MutableClock clock = new MutableClock(START);
        InMemorySlidingWindowStrategy strategy = new InMemorySlidingWindowStrategy(clock);
        RateLimitConfig config = new RateLimitConfig(1, 10); // 1 requests per 10 seconds

        // t=0: make 1 request
        assertTrue(strategy.tryConsume("client-x",config).allowed());

        // t=9 :
        clock.advanceSeconds(9);
        RateLimitResult result9 = strategy.tryConsume("client-x", config);
        assertFalse(result9.allowed());
        assertEquals(1, result9.retryAfterSeconds());
        assertEquals(START.plusSeconds(10), result9.resetAt());

        // t=10 => 9 + 1:
        clock.advanceSeconds(1);
        RateLimitResult result10 = strategy.tryConsume("client-x", config);
        assertTrue(result10.allowed());
        assertEquals(0,result10.remainingTokens());
        assertEquals(0, result10.retryAfterSeconds());
        assertEquals(START.plusSeconds(20), result10.resetAt());

        // another request at the same instant (t=10), should be denied
        RateLimitResult denied = strategy.tryConsume("client-x", config);
        assertFalse(denied.allowed());
        assertEquals(10, denied.retryAfterSeconds());
        assertEquals(START.plusSeconds(20), denied.resetAt());
    }
}