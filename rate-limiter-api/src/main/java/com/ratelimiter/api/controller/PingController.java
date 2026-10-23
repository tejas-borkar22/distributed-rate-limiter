package com.ratelimiter.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Minimal endpoint for demos and load tests; the filter rate-limits it, not this class
@RestController
@RequestMapping("/api") // all paths in this class start with /api
public class PingController {

    private static final String PONG_RESPONSE = "pong - ";

    // GET /api/ping returns plain text "pong"
    @GetMapping("/ping")
    public String ping() {
        return PONG_RESPONSE;
    }
}