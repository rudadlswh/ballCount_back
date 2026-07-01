package com.kbo.crawlerapi.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashSupport {

    private HashSupport() {
    }

    public static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String sha256Prefix(String value, int bytes) {
        return sha256BytePrefix(value, bytes);
    }

    public static String sha256BytePrefix(String value, int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        return sha256Hex(value).substring(0, Math.min(bytes * 2, 64));
    }

    public static String sha256HexPrefix(String value, int length) {
        if (length < 0) {
            throw new IllegalArgumentException("length must not be negative");
        }
        return sha256Hex(value).substring(0, Math.min(length, 64));
    }
}
