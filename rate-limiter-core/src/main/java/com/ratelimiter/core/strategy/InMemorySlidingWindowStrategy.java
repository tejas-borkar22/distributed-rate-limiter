package com.ratelimiter.core.strategy;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import com.ratelimiter.core.RateLimitStrategy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory Sliding Window Log implementation.
 * Each client key gets a log of request timestamps. A request is allowed
 * only if fewer than `capacity` timestamps fall within the last `windowSeconds`.
 * Not safe across multiple server instances, see the Redis-backed strategy for that.
 */
public class InMemorySlidingWindowStrategy implements RateLimitStrategy {

    // TODO: what data structure holds each client's log of timestamps?
    // Think about what operations you need: add a timestamp at the back,
    // remove old timestamps from the front, and count what's left.
    // A plain ArrayList works, but consider what happens if you need to
    // repeatedly remove from the FRONT of a list, what's the time complexity
    // of that, and is there a structure better suited for "remove from front,
    // add to back"?
    private final Map<String, UserWindow> requestLogs = new ConcurrentHashMap<>();

    private final Clock clock;

    public InMemorySlidingWindowStrategy(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RateLimitResult tryConsume(String clientKey, RateLimitConfig config) {
        long capacity = config.capacity();

        // Step 1: fetch or create this client's log.
        UserWindow slidingUserWindow = requestLogs.computeIfAbsent(
                clientKey,
                id -> new UserWindow());

        Instant now = clock.instant();
        // Step 2: lock on the log.
        synchronized (slidingUserWindow){

            // Step 3: evict expired timestamps (Remove expired requests).
            // Compute the cutoff time /windowStart
            // Remove every timestamp in the log that's older than this cutoff.
            // cutOff Time
            Instant windowStart = now.minusSeconds(config.windowSeconds());

            while (!slidingUserWindow.timestamps.isEmpty()
                    && !slidingUserWindow.timestamps.peekFirst().isAfter(windowStart)) {
                slidingUserWindow.timestamps.removeFirst();
            }

            // resetAt: when the oldest surviving timestamp will age out of the window.
            // Defaults to now + window if the log is empty, so peekFirst() is never
            // called on an empty deque.
            Instant resetAt = now.plusSeconds(config.windowSeconds());
            if(!slidingUserWindow.timestamps.isEmpty()){
                Instant oldest = slidingUserWindow.timestamps.peekFirst();
                // resetAt = oldestTimeStamp + windowSize
                resetAt = oldest.plusSeconds(config.windowSeconds());
            }

            // Step 4: count what's left, compare to config.capacity().
            if (slidingUserWindow.timestamps.size() >= capacity) {
                long retryAfter = Duration.between(now, resetAt).getSeconds();
                return RateLimitResult.deny(resetAt, retryAfter);
            }

            // Step 5: log.size() < capacity: allow, add current timestamp to the log
            //   return RateLimitResult.allow(remaining, resetAt)
            slidingUserWindow.timestamps.addLast(now);
            long remainingTokens = capacity - slidingUserWindow.timestamps.size();
            return RateLimitResult.allow(remainingTokens, resetAt);
        }
    }

    private static class UserWindow {
        private final Deque<Instant> timestamps =
                new ArrayDeque<>();
    }
}