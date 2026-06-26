package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
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
        long maxBytes = properties.getRegistrationRequestMaxBytes();
        if (maxBytes <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > maxBytes) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        byte[] body = readBodyWithinLimit(request, maxBytes);
        if (body == null) {
            response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
            return;
        }
        filterChain.doFilter(new CachedBodyHttpServletRequest(request, body), response);
    }

    private byte[] readBodyWithinLimit(HttpServletRequest request, long maxBytes) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long totalBytes = 0;
        int read;
        var inputStream = request.getInputStream();
        while ((read = inputStream.read(buffer)) != -1) {
            totalBytes += read;
            if (totalBytes > maxBytes) {
                return null;
            }
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }
}
