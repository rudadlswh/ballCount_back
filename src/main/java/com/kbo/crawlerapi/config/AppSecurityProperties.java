package com.kbo.crawlerapi.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.security")
public class AppSecurityProperties {

    private String adminApiKey;
    private long registrationRequestMaxBytes = 32 * 1024;
    private boolean registrationRateLimitEnabled = true;
    private int registrationRateLimitMaxRequests = 12;
    private Duration registrationRateLimitWindow = Duration.ofMinutes(1);

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

    public boolean isRegistrationRateLimitEnabled() {
        return registrationRateLimitEnabled;
    }

    public void setRegistrationRateLimitEnabled(boolean registrationRateLimitEnabled) {
        this.registrationRateLimitEnabled = registrationRateLimitEnabled;
    }

    public int getRegistrationRateLimitMaxRequests() {
        return registrationRateLimitMaxRequests;
    }

    public void setRegistrationRateLimitMaxRequests(int registrationRateLimitMaxRequests) {
        this.registrationRateLimitMaxRequests = registrationRateLimitMaxRequests;
    }

    public Duration getRegistrationRateLimitWindow() {
        return registrationRateLimitWindow;
    }

    public void setRegistrationRateLimitWindow(Duration registrationRateLimitWindow) {
        this.registrationRateLimitWindow = registrationRateLimitWindow;
    }
}
