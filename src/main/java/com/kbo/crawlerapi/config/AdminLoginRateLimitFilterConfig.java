package com.kbo.crawlerapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminLoginRateLimitFilterConfig {
    @Bean
    public FilterRegistrationBean<AdminLoginRateLimitFilter> adminLoginRateLimitFilter(AppSecurityProperties properties) {
        var registration = new FilterRegistrationBean<>(new AdminLoginRateLimitFilter(properties));
        registration.addUrlPatterns("/admin/login");
        registration.setOrder(2);
        return registration;
    }
}
