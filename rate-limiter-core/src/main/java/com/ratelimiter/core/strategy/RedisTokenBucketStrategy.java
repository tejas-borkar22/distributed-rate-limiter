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

/* Redis-backed Token Bucket Strategy. All state lives in Redis, so every server shares it. */
public class RedisTokenBucketStrategy implements RateLimitStrategy {

    private final JedisPool pool;
    private final Clock clock;
    private final String script;
    private static final String RL_TOKEN_BUCKET_PREFIX = "rl:tb:";
    private static final String LUA_SCRIPT_FILE_PATH = "/lua/token_bucket.lua";

    public RedisTokenBucketStrategy(JedisPool pool, Clock clock) {
        this.pool = pool;
        this.clock = clock;
        // TODO 1: load /lua/token_bucket.lua from the classpath into `script`.
        this.script = loadScript();
    }

    private static String loadScript(){
        try (InputStream in = RedisTokenBucketStrategy.class.getResourceAsStream(LUA_SCRIPT_FILE_PATH)) {
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
        // TODO 2: build the Redis key. Use a prefix, e.g. "rl:tb:" + clientKey,
        //   so rate limiter keys never collide with anything else in Redis.
        String redisClientKey =  RL_TOKEN_BUCKET_PREFIX + clientKey;

        // TODO 3: borrow a connection and run the script.
        String capacityArg = String.valueOf(config.capacity());
        String windowArg = String.valueOf(config.windowSeconds());

        try (Jedis jedis = pool.getResource()) {
            Object raw = jedis.eval(script, List.of(redisClientKey), List.of(capacityArg, windowArg));
            // The script returns a table, which Jedis hands back as a List of Longs. Cast: List<?> result = (List<?>) raw;
            List<?> result = (List<?>) raw;
            // read the three values, then build and return the RateLimitResult
            Long tokenStatus = (Long) result.get(0);
            Long remainingTokens = (Long) result.get(1);
            Long retryAfterMs = (Long) result.get(2);

            // TODO 4: convert to a RateLimitResult.
            //  allowed (get(0) == 1): RateLimitResult.allow(remaining, resetAt)
            if(tokenStatus.equals(1L)){
                //  resetAt: clock.instant().plusSeconds(config.windowSeconds()), same as the in-memory version.
                Instant resetAt = clock.instant().plusSeconds(config.windowSeconds());
                return RateLimitResult.allow(remainingTokens , resetAt);
            }else {
                //  denied: RateLimitResult.deny(resetAt, retryAfterSeconds)
                // For the denied request : resetAt = now + time until ONE token becomes available
                Instant resetAt = clock.instant().plusMillis(retryAfterMs);
                // retryAfterSeconds comes from retry_after_ms.
                // ROUND UP to whole seconds (a wait of 1989 ms must become 2, not 1).
                long retryAfterSeconds = (retryAfterMs + 999) / 1000;
                return RateLimitResult.deny(resetAt, retryAfterSeconds);
            }
        }
    }
}