package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ClientRequestLimiterTest {
    @Test
    void concurrentAttemptsCannotExceedQuota() throws Exception {
        ClientRequestLimiter limiter = new ClientRequestLimiter(Clock.systemUTC());
        var executor = Executors.newFixedThreadPool(8);
        try {
            var results = executor.invokeAll(IntStream.range(0, 100)
                    .<java.util.concurrent.Callable<Boolean>>mapToObj(i -> () -> limiter.tryAcquire("ip", 5, Duration.ofMinutes(1)))
                    .toList());
            int allowed = 0;
            for (var result : results) if (result.get()) allowed++;
            assertThat(allowed).isEqualTo(5);
            assertThat(limiter.tryAcquire("another-ip", 5, Duration.ofMinutes(1))).isTrue();
        } finally { executor.shutdownNow(); }
    }

    @Test
    void quotasExpireAndFullTrackingTableFailsClosed() {
        MutableClock clock = new MutableClock();
        ClientRequestLimiter limiter = new ClientRequestLimiter(clock);
        Duration window = Duration.ofMinutes(1);
        for (int i = 0; i < 10_000; i++) assertThat(limiter.tryAcquire("ip-" + i, 1, window)).isTrue();
        assertThat(limiter.tryAcquire("overflow", 1, window)).isFalse();
        assertThat(limiter.tryAcquire("ip-0", 1, window)).isFalse();
        clock.now = clock.now.plus(window);
        assertThat(limiter.tryAcquire("overflow", 1, window)).isTrue();
        assertThat(limiter.tryAcquire("ip-0", 1, window)).isTrue();
    }

    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
