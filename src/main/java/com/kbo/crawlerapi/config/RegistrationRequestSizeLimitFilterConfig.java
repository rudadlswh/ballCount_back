package com.kbo.crawlerapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegistrationRequestSizeLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<RegistrationRequestSizeLimitFilter> registrationRequestSizeLimitFilter(AppSecurityProperties properties) {
        FilterRegistrationBean<RegistrationRequestSizeLimitFilter> registration = new FilterRegistrationBean<>(
                new RegistrationRequestSizeLimitFilter(properties)
        );
        registration.addUrlPatterns(
                "/devices/register",
                "/devices/unregister",
                "/devices/live-activities/register",
                "/live-activities/register",
                "/devices/live-activities/push-to-start/register",
                "/live-activities/push-to-start/register",
                "/api/v1/attendance",
                "/api/v1/games/reconcile-stale",
                "/games/reconcile-stale",
                "/admin/*",
                "/internal/*"
        );
        registration.setOrder(1);
        return registration;
    }
}
