package com.ratelimiter.core;

/**
 * Contract for a rate-limiting algorithm.
 * Implementations decide, per client key, whether a request should be allowed
 * right now, based on a configured limit.
 */

public interface RateLimitStrategy {

    /**
     * Attempts to consume one unit of capacity for the given client.
     *
     * @param clientKey identifies the caller being rate-limited, e.g. an API key or user ID
     * @param config    the limit rule to apply for this check
     * @return the result of the check: allowed or denied, with remaining capacity and reset time
     */
    RateLimitResult tryConsume(String clientKey, RateLimitConfig config);
}