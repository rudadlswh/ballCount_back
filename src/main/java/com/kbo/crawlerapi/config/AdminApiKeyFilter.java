package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminApiKeyFilter extends OncePerRequestFilter {

    public static final String ADMIN_API_KEY_HEADER = "X-Admin-Api-Key";
    public static final String ADMIN_KEY_HEADER = "X-Admin-Key";

    private final AppSecurityProperties properties;
    private final KboAdminProperties adminProperties;

    public AdminApiKeyFilter(AppSecurityProperties properties) {
        this(properties, new KboAdminProperties());
    }

    public AdminApiKeyFilter(AppSecurityProperties properties, KboAdminProperties adminProperties) {
        this.properties = properties;
        this.adminProperties = adminProperties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isProtectedPath(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!apiKeyMatches(request.getHeader(ADMIN_KEY_HEADER))
                && !apiKeyMatches(request.getHeader(ADMIN_API_KEY_HEADER))) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isProtectedPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path.equals("/admin")
                || path.startsWith("/admin/")
                || path.equals("/internal")
                || path.startsWith("/internal/")
                || path.equals("/api/v1/games/reconcile-stale")
                || path.equals("/games/reconcile-stale");
    }

    private boolean apiKeyMatches(String candidate) {
        String expected = configuredAdminApiKey();
        if (expected == null || expected.isBlank() || candidate == null || candidate.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String configuredAdminApiKey() {
        if (adminProperties != null
                && adminProperties.getApiKey() != null
                && !adminProperties.getApiKey().isBlank()) {
            return adminProperties.getApiKey();
        }
        return properties == null ? null : properties.getAdminApiKey();
    }
}
