package com.ratelimiter.api.filter;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.ratelimiter.api.config.RateLimiterProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import com.ratelimiter.core.RateLimitStrategy;

// Runs before every request; lets it through or rejects it with 429 status(too many requests).
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RESET_HEADER = "X-RateLimit-Reset";
    private static final String RETRY_AFTER_HEADER = "Retry-After";

    private final RateLimitStrategy strategy;
    private final RateLimitConfig config;
    private final boolean failOpen;

    // Spring injects both beans from RateLimiterConfiguration
    public RateLimitFilter(RateLimitStrategy strategy, RateLimitConfig config, RateLimiterProperties props) {
        this.strategy = strategy;
        this.config = config;
        this.failOpen = props.failOpen();  // read the setting once at startup
    }

    // Skip rate limiting for monitoring endpoints so health checks never get 429 response.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientKey = resolveClientKey(request);
        RateLimitResult result;
        try {
            result = strategy.tryConsume(clientKey, config);
        } catch (RuntimeException e) {
            // Limiter backend failed (e.g. Redis down). Passing e keeps the stack trace in the log
            log.warn("Rate limiter check failed; failOpen={}", failOpen, e);

            if (failOpen) {
                // allow: the request continues to the controller
                chain.doFilter(request, response);
            } else {
                // reject with a generic 503;
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        "{\"error\":\"service_unavailable\","
                                + "\"message\":\"Service temporarily unavailable. Please try again later.\"}");
            }
            return;
        }

        // Set headers BEFORE continuing: once the controller writes, headers may be ignored
        response.setHeader(LIMIT_HEADER, String.valueOf(config.capacity()));
        response.setHeader(REMAINING_HEADER, String.valueOf(result.remainingTokens()));
        response.setHeader(RESET_HEADER, String.valueOf(result.resetAt().getEpochSecond())); // epoch seconds

        // request is denied
        if (!result.allowed()) {
            long retryAfter = result.retryAfterSeconds();

            response.setHeader(RETRY_AFTER_HEADER, String.valueOf(retryAfter));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());

            // Human-readable detail lives in the body, not in headers
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\","
                            + "\"message\":\"Too many requests. Try again in " + retryAfter + " seconds.\","
                            + "\"retryAfterSeconds\":" + retryAfter + "}");

            return; // stop here: do NOT call chain.doFilter() on deny
        }
        chain.doFilter(request, response); // allowed: pass to next filter / controller
    }

    // API key if present, else the caller's IP, so a missing key never becomes a shared bucket.
    // Note: behind a proxy/load balancer the IP is the proxy's; use X-Forwarded-For then.
    private String resolveClientKey(HttpServletRequest request) {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            return request.getRemoteAddr();
        }
        return apiKey.trim();
    }
}