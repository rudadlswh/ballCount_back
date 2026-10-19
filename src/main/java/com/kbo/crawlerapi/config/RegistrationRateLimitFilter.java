package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class RegistrationRateLimitFilter extends OncePerRequestFilter {
    private final AppSecurityProperties properties;
    private final ClientRequestLimiter limiter;

    public RegistrationRateLimitFilter(AppSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RegistrationRateLimitFilter(AppSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.limiter = new ClientRequestLimiter(clock);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        int maxRequests = properties.getRegistrationRateLimitMaxRequests();
        if (properties.isRegistrationRateLimitEnabled() && maxRequests > 0) {
            Duration window = ClientRequestLimiter.windowOrDefault(properties.getRegistrationRateLimitWindow());
            if (!limiter.tryAcquire(operation(request) + ":" + request.getRemoteAddr(), maxRequests, window)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", Long.toString(Math.max(1, window.toSeconds())));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private String operation(HttpServletRequest request) {
        return switch (SecurityRequestPath.withinApplication(request)) {
            case "/devices/live-activities/register", "/live-activities/register" -> "live-activity";
            case "/devices/live-activities/push-to-start/register", "/live-activities/push-to-start/register" -> "push-to-start";
            case "/api/v1/attendance" -> "attendance";
            default -> "device";
        };
    }
}
