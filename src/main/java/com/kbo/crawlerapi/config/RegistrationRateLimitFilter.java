package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class RegistrationRateLimitFilter extends OncePerRequestFilter {

    private final AppSecurityProperties properties;
    private final Clock clock;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private volatile Instant nextCleanupAt = Instant.EPOCH;

    public RegistrationRateLimitFilter(AppSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RegistrationRateLimitFilter(AppSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isRegistrationRateLimitEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isLimited(rateLimitKey(request))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private String rateLimitKey(HttpServletRequest request) {
        return request.getRequestURI() + ":" + clientIp(request);
    }

    private String clientIp(HttpServletRequest request) {
        return "ip:" + request.getRemoteAddr();
    }

    private boolean isLimited(String key) {
        int maxRequests = properties.getRegistrationRateLimitMaxRequests();
        if (maxRequests <= 0) {
            return false;
        }
        Duration window = properties.getRegistrationRateLimitWindow();
        if (window == null || window.isZero() || window.isNegative()) {
            window = Duration.ofMinutes(1);
        }
        Instant now = Instant.now(clock);
        Duration effectiveWindow = window;
        WindowCounter counter = counters.compute(key, (ignored, current) -> {
            if (current == null || !now.isBefore(current.resetAt())) {
                return new WindowCounter(now.plus(effectiveWindow), 1);
            }
            return new WindowCounter(current.resetAt(), current.count() + 1);
        });
        cleanupExpiredCounters(now, effectiveWindow);
        return counter.count() > maxRequests;
    }

    private void cleanupExpiredCounters(Instant now, Duration window) {
        if (now.isBefore(nextCleanupAt)) {
            return;
        }
        synchronized (this) {
            if (now.isBefore(nextCleanupAt)) {
                return;
            }
            counters.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt()));
            nextCleanupAt = now.plus(window);
        }
    }

    private record WindowCounter(Instant resetAt, int count) {
    }
}
