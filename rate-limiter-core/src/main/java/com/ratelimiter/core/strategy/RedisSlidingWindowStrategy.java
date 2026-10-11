package com.ratelimiter.core.strategy;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import com.ratelimiter.core.RateLimitStrategy;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

public class RedisSlidingWindowStrategy implements RateLimitStrategy {

    private static final String RL_SLIDING_WINDOW_PREFIX = "rl:sw:";
    private static final String LUA_SCRIPT_FILE_PATH = "/lua/sliding_window.lua";

    private final JedisPool pool;
    private final Clock clock;
    private final String script;

    public RedisSlidingWindowStrategy(JedisPool pool, Clock clock) {
        this.pool = pool;
        this.clock = clock;
        // Step 1: load /lua/sliding_window.lua from the classpath into `script`.
        this.script = loadScript();
    }

    private static String loadScript() {
        try (InputStream in = RedisSlidingWindowStrategy.class.getResourceAsStream(LUA_SCRIPT_FILE_PATH)) {
            if(in == null) {
                throw new IllegalStateException("Lua script not found on classpath: " + LUA_SCRIPT_FILE_PATH);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read Lua script: " + LUA_SCRIPT_FILE_PATH, e);
        }
    }

    @Override
    public RateLimitResult tryConsume(String clientKey, RateLimitConfig config) {
        // Step 2: build the Redis key. Use a prefix, e.g. "rl:sw:" + clientKey,
        // so rate limiter keys never collide with anything else in Redis.
        String redisKey = RL_SLIDING_WINDOW_PREFIX + clientKey;
        String capacityArg = String.valueOf(config.capacity());
        String windowArg = String.valueOf(config.windowSeconds());

        // Step 3: borrow a connection and run the script.
        try (Jedis jedis = pool.getResource()) {
            Object raw = jedis.eval(script, List.of(redisKey), List.of(capacityArg, windowArg));
            List<?> result = (List<?>) raw;

            // read the three values, then build and return the RateLimitResult
            Long tokenStatus = (Long) result.get(0);
            Long remainingTokens = (Long) result.get(1);
            Long retryAfterMs = (Long) result.get(2);

            if (tokenStatus.equals(1L)) {
                Instant resetAt = clock.instant().plusSeconds(config.windowSeconds());
                return RateLimitResult.allow(remainingTokens, resetAt);
            } else {
                Instant resetAt = clock.instant().plusMillis(retryAfterMs);
                long retryAfterSeconds = (retryAfterMs + 999) / 1000;
                return RateLimitResult.deny(resetAt, retryAfterSeconds);
            }
        }
    }
}