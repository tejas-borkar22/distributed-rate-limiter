package com.ratelimiter.api.filter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitResult;
import com.ratelimiter.core.RateLimitStrategy;

// Fixed settings so the tests don't depend on application.yml values
@SpringBootTest(properties = {
        "ratelimiter.backend=IN_MEMORY",
        "ratelimiter.capacity=5",
        "ratelimiter.window-seconds=60",
        "ratelimiter.fail-open=true"
})
@AutoConfigureMockMvc
class RateLimitFilterTest {

    @Autowired MockMvc mockMvc;

    // Replaces the real strategy: each test decides what tryConsume returns
    @MockitoBean RateLimitStrategy strategy;

    // 1. Strategy says "allowed" -> controller runs, headers are added
    @Test
    void allowedRequest_returns200WithRateLimitHeaders() throws Exception {
        Instant resetAt = Instant.now().plusSeconds(60);
        when(strategy.tryConsume(anyString(), any(RateLimitConfig.class)))
                .thenReturn(RateLimitResult.allow(4, resetAt));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pong")))           // controller ran
                .andExpect(header().string("X-RateLimit-Limit", "5"))       // from ratelimiter.capacity above
                .andExpect(header().string("X-RateLimit-Remaining", "4"))
                .andExpect(header().string("X-RateLimit-Reset",
                        String.valueOf(resetAt.getEpochSecond())));
    }

    // 2. Strategy says "denied" -> 429, controller never runs
    @Test
    void deniedRequest_returns429WithRetryAfterAndJsonBody() throws Exception {
        when(strategy.tryConsume(anyString(), any(RateLimitConfig.class)))
                .thenReturn(RateLimitResult.deny(Instant.now().plusSeconds(20), 20));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "test-key"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "20"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(containsString("rate_limit_exceeded")))
                .andExpect(content().string(not(containsString("pong")))); // controller was skipped
    }

    // 3. Strategy throws (e.g. Redis down) + fail-open=true -> request still passes
    @Test
    void strategyThrows_failOpen_requestPassesWithoutRateLimitHeaders() throws Exception {
        when(strategy.tryConsume(anyString(), any(RateLimitConfig.class)))
                .thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("pong")))
                .andExpect(header().doesNotExist("X-RateLimit-Limit")); // no real numbers to report
    }

    // 4. No X-API-Key header -> the client's IP address becomes the key
    @Test
    void missingApiKey_usesRemoteAddressAsClientKey() throws Exception {
        when(strategy.tryConsume(anyString(), any(RateLimitConfig.class)))
                .thenReturn(RateLimitResult.allow(4, Instant.now().plusSeconds(60)));

        mockMvc.perform(get("/api/ping"))   // no header on purpose
                .andExpect(status().isOk());

        // MockMvc's default remote address is 127.0.0.1
        verify(strategy).tryConsume(eq("127.0.0.1"), any(RateLimitConfig.class));
    }

    // 5. Health checks must bypass the limiter completely
    @Test
    void actuatorHealth_isNotRateLimited() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        verifyNoInteractions(strategy);   // the filter never asked the strategy
    }
}