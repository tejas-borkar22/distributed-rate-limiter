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
class RedisTokenBucketStrategyTest {
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private JedisPool pool() {
        return new JedisPool(redis.getHost(), redis.getMappedPort(6379));
    }

    // Each test uses its own random client key, so tests never see
    // leftover data from each other, even though they share one container.
    private String newClientKey() {
        return "client-" + UUID.randomUUID();
    }

    @Test
    void allowsRequestsUpToCapacityThenDenies() {
        JedisPool pool = pool();
        RedisTokenBucketStrategy strategy = new RedisTokenBucketStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(3, 10); // 3 per 10s
        String client = newClientKey();

        assertTrue(strategy.tryConsume(client, config).allowed());
        assertTrue(strategy.tryConsume(client, config).allowed());
        assertTrue(strategy.tryConsume(client, config).allowed());

        RateLimitResult denied = strategy.tryConsume(client, config);
        assertFalse(denied.allowed());
        assertEquals(0, denied.remainingTokens());

        pool.close();
    }

    @Test
    void refillsAfterRealWaiting() throws InterruptedException {
        // GOTCHA: unlike your in-memory tests, you CANNOT fake time here.
        // The Lua script reads Redis's own clock (TIME), not a Java Clock
        // you control. To test refill, you must actually wait.
        JedisPool pool = pool();
        RedisTokenBucketStrategy strategy = new RedisTokenBucketStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(2, 2); // 2 per 2s = 1 token/sec
        String client = newClientKey();

        strategy.tryConsume(client, config);
        strategy.tryConsume(client, config);
        assertFalse(strategy.tryConsume(client, config).allowed());

        Thread.sleep(1100); // just over 1 second, 1 token should regenerate

        assertTrue(strategy.tryConsume(client, config).allowed());

        pool.close();
    }

    @Test
    void differentClientsHaveIndependentBuckets() {
        JedisPool pool = pool();
        RedisTokenBucketStrategy strategy = new RedisTokenBucketStrategy(pool, Clock.systemUTC());
        RateLimitConfig config = new RateLimitConfig(1, 60);

        String client1 = newClientKey();
        assertTrue(strategy.tryConsume(client1, config).allowed());
        assertFalse(strategy.tryConsume(client1, config).allowed()); // client-1 exhausted

        // client2 is unaffected by client1's usage
        String client2 = newClientKey();
        assertTrue(strategy.tryConsume(client2, config).allowed());

        pool.close();
    }
}