package com.kbo.crawlerapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminApiKeyFilterConfig {

    @Bean
    public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilter(AppSecurityProperties properties) {
        FilterRegistrationBean<AdminApiKeyFilter> registration = new FilterRegistrationBean<>(
                new AdminApiKeyFilter(properties)
        );
        registration.addUrlPatterns("/admin", "/admin/*");
        registration.setOrder(0);
        return registration;
    }
}
