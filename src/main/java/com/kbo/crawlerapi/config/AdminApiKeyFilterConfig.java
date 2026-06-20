package com.kbo.crawlerapi.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AdminApiKeyFilterConfig {

    @Bean
    public FilterRegistrationBean<AdminApiKeyFilter> adminApiKeyFilter(
            AppSecurityProperties properties,
            KboAdminProperties adminProperties
    ) {
        FilterRegistrationBean<AdminApiKeyFilter> registration = new FilterRegistrationBean<>(
                new AdminApiKeyFilter(properties, adminProperties)
        );
        registration.addUrlPatterns("/admin", "/admin/*", "/internal", "/internal/*");
        registration.setOrder(0);
        return registration;
    }
}
