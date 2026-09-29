package com.ratelimiter.core.strategy;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import com.ratelimiter.core.RateLimitStrategy;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Token Bucket implementation. Each client key gets its own bucket,
 * which refills continuously based on elapsed time rather than on a fixed tick.
 * Not safe across multiple server instances, see the Redis-backed strategy for that.
 */
public class InMemoryTokenBucketStrategy implements RateLimitStrategy {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryTokenBucketStrategy(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryConsume(String clientKey, RateLimitConfig config) {
        Bucket bucket = buckets.computeIfAbsent(clientKey, key -> new Bucket(config.capacity(), clock.instant()));

        synchronized (bucket) {
            refill(bucket, config);

            Instant resetAt = clock.instant().plusSeconds(config.windowSeconds());

            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return RateLimitResult.allow((long) bucket.tokens, resetAt);
            }

            long retryAfter = secondsUntilNextToken(config);
            return RateLimitResult.deny(resetAt, retryAfter);
        }
    }

    private void refill(Bucket bucket, RateLimitConfig config) {
        Instant now = clock.instant();
        long elapsedSeconds = bucket.lastRefill.until(now, java.time.temporal.ChronoUnit.SECONDS);

        if (elapsedSeconds <= 0) {
            return;
        }

        double tokensPerSecond = (double) config.capacity() / config.windowSeconds();
        double tokensToAdd = elapsedSeconds * tokensPerSecond;

        bucket.tokens = Math.min(config.capacity(), bucket.tokens + tokensToAdd);
        bucket.lastRefill = now;
    }

    private long secondsUntilNextToken(RateLimitConfig config) {
        double tokensPerSecond = (double) config.capacity() / config.windowSeconds();
        return (long) Math.ceil(1.0 / tokensPerSecond);
    }

    private static class Bucket {
        double tokens;
        Instant lastRefill;

        Bucket(long initialTokens, Instant now) {
            this.tokens = initialTokens;
            this.lastRefill = now;
        }
    }
}