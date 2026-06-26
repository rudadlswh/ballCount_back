package com.kbo.crawlerapi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class RegistrationRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_INSTALLATION_ID_KEY_LENGTH = 100;

    private final AppSecurityProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();

    public RegistrationRateLimitFilter(AppSecurityProperties properties) {
        this(properties, Clock.systemUTC(), new ObjectMapper());
    }

    RegistrationRateLimitFilter(AppSecurityProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!properties.isRegistrationRateLimitEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        byte[] body = cachedOrReadBody(request);
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request, body);
        if (isLimited(rateLimitKey(request, body))) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            return;
        }
        filterChain.doFilter(cachedRequest, response);
    }

    private byte[] cachedOrReadBody(HttpServletRequest request) throws IOException {
        if (request instanceof CachedBodyHttpServletRequest cachedRequest) {
            return cachedRequest.cachedBody();
        }
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        request.getInputStream().transferTo(outputStream);
        return outputStream.toByteArray();
    }

    private String rateLimitKey(HttpServletRequest request, byte[] body) {
        String installationId = installationId(body);
        String subject = installationId == null ? clientIp(request) : "installation:" + installationId;
        return request.getRequestURI() + ":" + subject;
    }

    private String installationId(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
            JsonNode value = node.get("installationId");
            if (value == null || !value.isTextual()) {
                return null;
            }
            String normalized = value.asText().trim();
            if (normalized.isEmpty() || normalized.length() > MAX_INSTALLATION_ID_KEY_LENGTH) {
                return null;
            }
            return normalized;
        } catch (IOException exception) {
            return null;
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return "ip:" + forwardedFor.split(",", 2)[0].trim();
        }
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
        cleanupExpiredCounters(now);
        return counter.count() > maxRequests;
    }

    private void cleanupExpiredCounters(Instant now) {
        counters.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().resetAt()));
    }

    private record WindowCounter(Instant resetAt, int count) {
    }
}
