package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class RegistrationRequestSizeLimitFilter extends OncePerRequestFilter {

    private final AppSecurityProperties properties;

    public RegistrationRequestSizeLimitFilter(AppSecurityProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long contentLength = request.getContentLengthLong();
        long maxBytes = properties.getRegistrationRequestMaxBytes();
        if (maxBytes > 0 && contentLength > maxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
