package com.kbo.crawlerapi.admin;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.admin-ui")
public class AdminUiProperties {

    private String username = "admin";
    private String password = "";
    private Duration sessionTimeout = Duration.ofMinutes(30);
    private Duration staleGameThreshold = Duration.ofMinutes(2);
    private int logCapacity = 2_000;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public Duration getSessionTimeout() {
        return sessionTimeout;
    }

    public void setSessionTimeout(Duration sessionTimeout) {
        this.sessionTimeout = sessionTimeout;
    }

    public Duration getStaleGameThreshold() {
        return staleGameThreshold;
    }

    public void setStaleGameThreshold(Duration staleGameThreshold) {
        this.staleGameThreshold = staleGameThreshold;
    }

    public int getLogCapacity() {
        return logCapacity;
    }

    public void setLogCapacity(int logCapacity) {
        this.logCapacity = logCapacity;
    }
}
