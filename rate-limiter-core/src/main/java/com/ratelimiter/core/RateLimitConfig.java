package com.ratelimiter.core;

/**
 * Configuration for a rate limit rule: how many requests are allowed
 * per time window, for a given client key.
 *
 * @param capacity      max requests allowed within the window
 * @param windowSeconds size of the time window, in seconds
 */
public record RateLimitConfig(long capacity, long windowSeconds) {
    public RateLimitConfig {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (windowSeconds <= 0) {
            throw new IllegalArgumentException("windowSeconds must be positive");
        }
    }
}