package com.kbo.crawlerapi.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class RegistrationInputNormalizer {

    private RegistrationInputNormalizer() {
    }

    public static String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "ios";
        }
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (!"ios".equals(normalized)) {
            throw new IllegalArgumentException("platform must be ios");
        }
        return normalized;
    }

    public static String normalizeClientEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if (!"sandbox".equals(normalized) && !"production".equals(normalized)) {
            throw new IllegalArgumentException("environment must be sandbox or production");
        }
        return normalized;
    }

    public static String normalizeApnsEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if ("development".equals(normalized) || "debug".equals(normalized)) {
            return "sandbox";
        }
        if ("release".equals(normalized)) {
            return "production";
        }
        return normalized;
    }

    public static String requireText(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    public static String optionalText(String value, String name, int maxLength) {
        String normalized = blankToNull(value);
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(name + " is too long");
        }
        return normalized;
    }

    public static String normalizeFavoriteTeamId(String favoriteTeamId, int maxLength) {
        String normalized = optionalText(favoriteTeamId, "favoriteTeamId", maxLength);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!TeamCatalog.isSupportedTeamCode(normalized)) {
            throw new IllegalArgumentException("favoriteTeamId is invalid");
        }
        return normalized;
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String tokenPrefix(String token) {
        return token == null ? null : token.substring(0, Math.min(8, token.length()));
    }

    public static String tokenFingerprint(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < Math.min(6, digest.length); index++) {
                builder.append(String.format("%02x", digest[index]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
    }

    public static boolean normalizedEquals(String left, String right) {
        String normalizedLeft = blankToNull(left);
        String normalizedRight = blankToNull(right);
        return normalizedLeft != null && normalizedLeft.equalsIgnoreCase(normalizedRight);
    }
}
