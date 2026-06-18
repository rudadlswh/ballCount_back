package com.kbo.crawlerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    private String adminApiKey;
    private long registrationRequestMaxBytes = 32 * 1024;

    public String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    public long getRegistrationRequestMaxBytes() {
        return registrationRequestMaxBytes;
    }

    public void setRegistrationRequestMaxBytes(long registrationRequestMaxBytes) {
        this.registrationRequestMaxBytes = registrationRequestMaxBytes;
    }
}
