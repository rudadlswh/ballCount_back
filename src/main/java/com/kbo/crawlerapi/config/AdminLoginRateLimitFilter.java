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

public class AdminLoginRateLimitFilter extends OncePerRequestFilter {
    private final AppSecurityProperties properties;
    private final ClientRequestLimiter limiter;

    public AdminLoginRateLimitFilter(AppSecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AdminLoginRateLimitFilter(AppSecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.limiter = new ClientRequestLimiter(clock);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/admin/login".equals(SecurityRequestPath.withinApplication(request))) {
            Duration window = ClientRequestLimiter.windowOrDefault(properties.getAdminLoginRateLimitWindow());
            if (!allowAttempt(request.getRemoteAddr(), window)) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", Long.toString(Math.max(1, window.toSeconds())));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private synchronized boolean allowAttempt(String client, Duration window) {
        return limiter.tryAcquire("client:" + client, Math.max(1, properties.getAdminLoginMaxAttempts()), window)
                && limiter.tryAcquire("global", Math.max(1, properties.getAdminLoginGlobalMaxAttempts()), window);
    }
}
