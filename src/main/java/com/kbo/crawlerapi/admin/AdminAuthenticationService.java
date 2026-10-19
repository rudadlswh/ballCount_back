package com.kbo.crawlerapi.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthenticationService {

    private final AdminUiProperties properties;

    public AdminAuthenticationService(AdminUiProperties properties) {
        this.properties = properties;
    }

    public boolean authenticate(String username, String password) {
        if (!isConfigured() || username == null || password == null) {
            return false;
        }
        return constantTimeEquals(properties.getUsername(), username)
                & constantTimeEquals(properties.getPassword(), password);
    }

    public boolean isConfigured() {
        return properties.getUsername() != null
                && !properties.getUsername().isBlank()
                && properties.getPassword() != null
                && !properties.getPassword().isBlank();
    }

    public int sessionTimeoutSeconds() {
        long seconds = properties.getSessionTimeout() == null ? 1_800 : properties.getSessionTimeout().toSeconds();
        return (int) Math.max(60, Math.min(Integer.MAX_VALUE, seconds));
    }

    public String maskedUsername() {
        String username = properties.getUsername();
        if (username == null || username.isBlank()) {
            return "미설정";
        }
        if (username.length() == 1) {
            return "*";
        }
        return username.charAt(0) + "*".repeat(Math.max(1, username.length() - 1));
    }

    public String maskedPassword() {
        return isConfigured() ? "••••••••" : "미설정";
    }

    private boolean constantTimeEquals(String expected, String candidate) {
        byte[] expectedDigest = digest(expected);
        byte[] candidateDigest = digest(candidate);
        return MessageDigest.isEqual(expectedDigest, candidateDigest);
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
