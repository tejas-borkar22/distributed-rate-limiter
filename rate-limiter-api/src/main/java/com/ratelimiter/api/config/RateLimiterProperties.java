package com.ratelimiter.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "ratelimiter")
public record RateLimiterProperties(
        Backend backend,          // IN_MEMORY or REDIS
        Algorithm algorithm,      // TOKEN_BUCKET or SLIDING_WINDOW
        int capacity,             // max requests / bucket size
        int windowSeconds,        // window or refill period
        Redis redis,               // only used when backend = REDIS
        @DefaultValue("true") boolean failOpen  // Redis down: true = allow, false = 503
) {
    public enum Backend { IN_MEMORY, REDIS }
    public enum Algorithm { TOKEN_BUCKET, SLIDING_WINDOW }

    public record Redis(String host, int port) { }
}