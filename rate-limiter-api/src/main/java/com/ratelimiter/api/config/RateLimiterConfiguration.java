package com.ratelimiter.api.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.JedisPool;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitStrategy;
import com.ratelimiter.core.strategy.InMemorySlidingWindowStrategy;
import com.ratelimiter.core.strategy.InMemoryTokenBucketStrategy;
import com.ratelimiter.core.strategy.RedisSlidingWindowStrategy;
import com.ratelimiter.core.strategy.RedisTokenBucketStrategy;

// Builds core's plain Java classes as Spring beans; yml decides which strategy.
@Configuration
public class RateLimiterConfiguration {

    // Shared time source (tests can pass a fake clock to strategies)
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    // Created only when backend=REDIS; doesn't connect until first use
    @Bean
    @ConditionalOnProperty(name = "ratelimiter.backend", havingValue = "REDIS")
    public JedisPool jedisPool(RateLimiterProperties props) {
        // Fail early with a clear message instead of a NullPointerException
        if (props.redis() == null) {
            throw new IllegalStateException("ratelimiter.redis.host/port required when backend=REDIS");
        }
        String host = props.redis().host();
        int port = props.redis().port();
        return new JedisPool(host, port); // Spring closes it on shutdown
    }

    // The rule: capacity requests per windowSeconds (invalid values fail at startup)
    @Bean
    public RateLimitConfig rateLimitConfig(RateLimiterProperties props) {
        long capacity = props.capacity();
        long windowSecs = props.windowSeconds();
        return new RateLimitConfig(capacity, windowSecs);
    }

    // Picks 1 of 4 strategies (backend x algorithm) from application.yml.
    // ObjectProvider = "waiter": JedisPool doesn't exist in IN_MEMORY mode,
    // so fetch it with getObject() only in REDIS cases (never cast it).
    @Bean
    public RateLimitStrategy rateLimitStrategy(
            RateLimiterProperties props, Clock clock, ObjectProvider<JedisPool> pool) {
        return switch (props.backend()) {
            case IN_MEMORY -> switch (props.algorithm()) {
                case TOKEN_BUCKET   -> new InMemoryTokenBucketStrategy(clock);
                case SLIDING_WINDOW -> new InMemorySlidingWindowStrategy(clock);
            };
            case REDIS -> switch (props.algorithm()) {
                case TOKEN_BUCKET   -> new RedisTokenBucketStrategy(pool.getObject(), clock);
                case SLIDING_WINDOW -> new RedisSlidingWindowStrategy(pool.getObject(), clock);
            };
        };
    }
}