package com.ratelimiter.core;

import java.time.Instant;

/**
 * The outcome of a rate limit check.
 *
 * @param allowed        whether the request is permitted
 * @param remainingTokens how many requests can still be made in the current window
 * @param resetAt        when the limit window resets (used for the X-RateLimit-Reset header later)
 * @param retryAfterSeconds if denied, how many seconds the caller should wait before retrying; 0 if allowed
 */
public record RateLimitResult(
        boolean allowed,
        long remainingTokens,
        Instant resetAt,
        long retryAfterSeconds
) {
    public static RateLimitResult allow(long remainingTokens, Instant resetAt) {
        return new RateLimitResult(true, remainingTokens, resetAt, 0);
    }

    public static RateLimitResult deny(Instant resetAt, long retryAfterSeconds) {
        return new RateLimitResult(false, 0, resetAt, retryAfterSeconds);
    }
}