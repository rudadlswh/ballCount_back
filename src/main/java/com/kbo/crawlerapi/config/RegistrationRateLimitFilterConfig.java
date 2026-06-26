package com.kbo.crawlerapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RegistrationRateLimitFilterConfig {

    @Bean
    public FilterRegistrationBean<RegistrationRateLimitFilter> registrationRateLimitFilter(AppSecurityProperties properties) {
        FilterRegistrationBean<RegistrationRateLimitFilter> registration = new FilterRegistrationBean<>(
                new RegistrationRateLimitFilter(properties)
        );
        registration.addUrlPatterns(
                "/devices/register",
                "/devices/live-activities/register",
                "/live-activities/register",
                "/devices/live-activities/push-to-start/register",
                "/live-activities/push-to-start/register"
        );
        registration.setOrder(2);
        return registration;
    }
}
