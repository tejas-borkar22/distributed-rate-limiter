package com.ratelimiter.core.strategy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/** Simple mutable Clock for simulating elapsed time in tests without real sleeps. */
public class MutableClock extends Clock {
    private Instant instant;

    MutableClock(Instant instant) {
        this.instant = instant;
    }

    void advanceSeconds(long seconds) {
        instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneOffset getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}