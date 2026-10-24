package com.ratelimiter.api.filter;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ratelimiter.core.RateLimitConfig;
import com.ratelimiter.core.RateLimitStrategy;

// Fixed settings so the tests don't depend on application.yml values
@SpringBootTest(properties = {
        "ratelimiter.backend=IN_MEMORY",
        "ratelimiter.capacity=5",
        "ratelimiter.window-seconds=60",
        "ratelimiter.fail-open=false"
})
@AutoConfigureMockMvc
class RateLimitFilterFailClosedTest {

    @Autowired MockMvc mockMvc;

    // Replaces the real strategy: each test decides what tryConsume returns
    @MockitoBean RateLimitStrategy strategy;

    // 1. Strategy throws (e.g. Redis down) + fail-open=false -> request does not process
    @Test
    void strategyThrows_failClosed_returns503() throws Exception {
        when(strategy.tryConsume(anyString(), any(RateLimitConfig.class)))
                .thenThrow(new RuntimeException("redis down"));

        mockMvc.perform(get("/api/ping").header("X-API-Key", "test-key"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("service_unavailable")))
                .andExpect(content().string(not(containsString("pong"))));  // controller was skipped
    }
}