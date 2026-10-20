// Rate limiter API layer: application entry point
package com.ratelimiter.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimiterApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApiApplication.class, args);
    }
}