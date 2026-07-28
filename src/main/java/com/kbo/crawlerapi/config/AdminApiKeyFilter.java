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
    public static final String ADMIN_SESSION_ATTRIBUTE = "KBO_ADMIN_AUTHENTICATED";

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
        applyAdminSecurityHeaders(response);
        if (isPublicAdminPath(request)
                || (isSessionAdminUiPath(request) && hasAuthenticatedAdminSession(request))) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!apiKeyMatches(request.getHeader(ADMIN_KEY_HEADER))
                && !apiKeyMatches(request.getHeader(ADMIN_API_KEY_HEADER))) {
            if (wantsHtml(request)) {
                response.sendRedirect(request.getContextPath() + "/admin/login");
                return;
            }
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isPublicAdminPath(HttpServletRequest request) {
        String path = pathWithoutContext(request);
        return path.equals("/admin/login") || path.startsWith("/admin/assets/");
    }

    private boolean isSessionAdminUiPath(HttpServletRequest request) {
        String path = pathWithoutContext(request);
        return path.equals("/admin")
                || path.equals("/admin/dashboard")
                || path.equals("/admin/issues")
                || path.equals("/admin/notifications")
                || path.equals("/admin/logs")
                || path.equals("/admin/logout")
                || path.startsWith("/admin/games/")
                || path.startsWith("/admin/fragments/");
    }

    private boolean hasAuthenticatedAdminSession(HttpServletRequest request) {
        var session = request.getSession(false);
        return session != null && Boolean.TRUE.equals(session.getAttribute(ADMIN_SESSION_ATTRIBUTE));
    }

    private boolean wantsHtml(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return "GET".equalsIgnoreCase(request.getMethod())
                && accept != null
                && accept.toLowerCase(java.util.Locale.ROOT).contains("text/html");
    }

    private void applyAdminSecurityHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
        );
    }

    private boolean isProtectedPath(HttpServletRequest request) {
        String path = pathWithoutContext(request);
        return path.equals("/admin")
                || path.startsWith("/admin/")
                || path.equals("/internal")
                || path.startsWith("/internal/")
                || path.equals("/api/v1/games/reconcile-stale")
                || path.equals("/games/reconcile-stale");
    }

    private String pathWithoutContext(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return path;
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
