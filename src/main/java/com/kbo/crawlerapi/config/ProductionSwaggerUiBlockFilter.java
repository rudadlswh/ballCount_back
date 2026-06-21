package com.kbo.crawlerapi.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile("production")
public class ProductionSwaggerUiBlockFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("/swagger-ui.html".equals(request.getRequestURI())) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
