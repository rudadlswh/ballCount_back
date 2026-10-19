package com.kbo.crawlerapi.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

final class ClientRequestLimiter {
    private static final int MAX_TRACKED_KEYS = 10_000;
    private final Clock clock;
    private final Map<String, Counter> counters = new HashMap<>();
    private Instant nextCleanupAt = Instant.EPOCH;

    ClientRequestLimiter(Clock clock) {
        this.clock = clock;
    }

    synchronized boolean tryAcquire(String key, int maxRequests, Duration window) {
        Instant now = clock.instant();
        if (!now.isBefore(nextCleanupAt) || counters.size() >= MAX_TRACKED_KEYS) {
            counters.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt));
            nextCleanupAt = now.plus(window);
        }
        Counter counter = counters.get(key);
        if (counter == null || !now.isBefore(counter.resetAt)) {
            // A full table fails closed rather than evicting an attacker's active quota.
            if (counter == null && counters.size() >= MAX_TRACKED_KEYS) return false;
            counters.put(key, new Counter(now.plus(window), 1));
            return true;
        }
        if (counter.count >= maxRequests) return false;
        counter.count++;
        return true;
    }

    static Duration windowOrDefault(Duration window) {
        return window == null || window.isZero() || window.isNegative() ? Duration.ofMinutes(1) : window;
    }

    private static final class Counter {
        private final Instant resetAt;
        private int count;
        private Counter(Instant resetAt, int count) { this.resetAt = resetAt; this.count = count; }
    }
}
