package com.ratelimiter.core.strategy;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import redis.clients.jedis.JedisPool;

import java.time.Clock;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisSlidingWindowLogStrategyTest {

    // Start a Redis container for integration tests.
    // Testcontainers manages the container lifecycle.
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    // Create a connection pool using the mapped Redis port.
    private JedisPool pool() {
        return new JedisPool(redis.getHost(), redis.getMappedPort(6379));
    }

    // Give each test a unique client key to prevent Redis data from one test affecting another test.
    private String newClientKey() {
        return "client-" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsUpToLimitThenDenies() {
        JedisPool pool = pool();
        RedisSlidingWindowStrategy strategy = new RedisSlidingWindowStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(3, 10); // 3 per 10s
        String client = newClientKey();

        assertTrue(strategy.tryConsume(client, config).allowed());
        assertTrue(strategy.tryConsume(client, config).allowed());
        assertTrue(strategy.tryConsume(client, config).allowed());

        RateLimitResult denied = strategy.tryConsume(client, config);
        assertFalse(denied.allowed());
        // Verify that no request capacity remains after reaching the limit.
        assertEquals(0, denied.remainingTokens());

        pool.close();
    }

    @Test
    void allowsRequestAfterOldestRequestExpires() throws InterruptedException {
        JedisPool pool = pool();
        RedisSlidingWindowStrategy strategy = new RedisSlidingWindowStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(2, 2); // 2 per 2 secs
        String client = newClientKey();

        assertTrue(strategy.tryConsume(client, config).allowed());
        assertTrue(strategy.tryConsume(client, config).allowed());

        // Verify that another request is denied while the window is full.
        assertFalse(strategy.tryConsume(client, config).allowed());

        // Wait until at least one recorded request falls outside the sliding window.
        Thread.sleep(2100);

        // Assert: The new request should be allowed once an old request expires.
        assertTrue(strategy.tryConsume(client, config).allowed());

        pool.close();
    }

    @Test
    void differentClientsHaveIndependentWindows() {
        JedisPool pool = pool();
        RedisSlidingWindowStrategy strategy = new RedisSlidingWindowStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(1, 10); // 1 per 10 secs

        String client1 = newClientKey();
        assertTrue(strategy.tryConsume(client1, config).allowed());
        assertFalse(strategy.tryConsume(client1, config).allowed()); // client-1 exhausted

        // Assert:
        String client2 = newClientKey();
        assertTrue(strategy.tryConsume(client2, config).allowed());

        pool.close();
    }

    @Test
    void requestsWithinWindowAreCountedCorrectly() throws InterruptedException {
        JedisPool pool = pool();
        RedisSlidingWindowStrategy strategy = new RedisSlidingWindowStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(3, 3); // 3 per 3 secs
        String client = newClientKey();

        // Request A: t ≈ 0 seconds.
        assertTrue(strategy.tryConsume(client, config).allowed());

        // Wait so requests have different timestamps.
        Thread.sleep(1100);

        // Request B: t ≈ 1.1 seconds.
        assertTrue(strategy.tryConsume(client, config).allowed());

        Thread.sleep(1100);

        // Request C: t ≈ 2.2 seconds.
        assertTrue(strategy.tryConsume(client, config).allowed());

        // All three requests are still within the 3-second window.
        assertFalse(strategy.tryConsume(client, config).allowed());

        Thread.sleep(1100);

        // Request A should now have expired. Requests B and C should still count.
        // Therefore, another request should be allowed.
        assertTrue(strategy.tryConsume(client, config).allowed());

        pool.close();
    }
}