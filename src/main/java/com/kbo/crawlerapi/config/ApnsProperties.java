package com.kbo.crawlerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.apns")
public class ApnsProperties {

    private boolean pushEnabled = false;
    private String teamId;
    private String keyId;
    private String bundleId;
    private String privateKey;
    private String env = "sandbox";

    public boolean isPushEnabled() {
        return pushEnabled;
    }

    public void setPushEnabled(boolean pushEnabled) {
        this.pushEnabled = pushEnabled;
    }

    public String getTeamId() {
        return teamId;
    }

    public void setTeamId(String teamId) {
        this.teamId = teamId;
    }

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getBundleId() {
        return bundleId;
    }

    public void setBundleId(String bundleId) {
        this.bundleId = bundleId;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getEnv() {
        return env;
    }

    public void setEnv(String env) {
        this.env = env;
    }

    public boolean isConfigPresent() {
        return hasText(teamId) && hasText(keyId) && hasText(bundleId) && hasText(privateKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
