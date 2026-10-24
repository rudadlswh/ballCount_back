package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminCsrfFilter extends OncePerRequestFilter {

    public static final String TOKEN_SESSION_ATTRIBUTE = "KBO_ADMIN_CSRF_TOKEN";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = SecurityRequestPath.withinApplication(request);
        if (path.startsWith("/admin/assets/")) {
            chain.doFilter(request, response);
            return;
        }
        boolean safeMethod = switch (request.getMethod()) {
            case "GET", "HEAD", "OPTIONS", "TRACE" -> true;
            default -> false;
        };
        if (safeMethod) {
            if ("GET".equals(request.getMethod())) {
                var session = request.getSession(true);
                synchronized (session) {
                    if (session.getAttribute(TOKEN_SESSION_ATTRIBUTE) == null) {
                        session.setAttribute(TOKEN_SESSION_ATTRIBUTE, UUID.randomUUID().toString());
                    }
                }
            }
        } else if (!Boolean.TRUE.equals(request.getAttribute(AdminApiKeyFilter.API_KEY_AUTHENTICATED_ATTRIBUTE))) {
            var session = request.getSession(false);
            String expected = session == null ? null : (String) session.getAttribute(TOKEN_SESSION_ATTRIBUTE);
            String candidate = request.getParameter("_csrf");
            if (expected == null || candidate == null || !MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8))) {
                // Login rotates the token, so a repeated successful login POST can carry the old one.
                if ("POST".equals(request.getMethod()) && "/admin/login".equals(path)
                        && session != null && Boolean.TRUE.equals(session.getAttribute(AdminApiKeyFilter.ADMIN_SESSION_ATTRIBUTE))) {
                    response.setStatus(HttpStatus.SEE_OTHER.value());
                    response.setHeader("Location", request.getContextPath() + "/admin/dashboard");
                } else {
                    response.setStatus(HttpStatus.FORBIDDEN.value());
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
