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
        if (SecurityRequestPath.withinApplication(request).startsWith("/admin/assets/")) {
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
                response.setStatus(HttpStatus.FORBIDDEN.value());
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
