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

    private int adminLoginMaxAttempts = 5;
    private int adminLoginGlobalMaxAttempts = 60;
    private Duration adminLoginRateLimitWindow = Duration.ofMinutes(1);
    private int liveStreamMaxConnections = 1_000;
    private int liveStreamMaxConnectionsPerClient = 10;

    public int getAdminLoginMaxAttempts() { return adminLoginMaxAttempts; }
    public void setAdminLoginMaxAttempts(int value) { adminLoginMaxAttempts = value; }
    public int getAdminLoginGlobalMaxAttempts() { return adminLoginGlobalMaxAttempts; }
    public void setAdminLoginGlobalMaxAttempts(int value) { adminLoginGlobalMaxAttempts = value; }
    public Duration getAdminLoginRateLimitWindow() { return adminLoginRateLimitWindow; }
    public void setAdminLoginRateLimitWindow(Duration value) { adminLoginRateLimitWindow = value; }
    public int getLiveStreamMaxConnections() { return liveStreamMaxConnections; }
    public void setLiveStreamMaxConnections(int value) { liveStreamMaxConnections = value; }
    public int getLiveStreamMaxConnectionsPerClient() { return liveStreamMaxConnectionsPerClient; }
    public void setLiveStreamMaxConnectionsPerClient(int value) { liveStreamMaxConnectionsPerClient = value; }

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
