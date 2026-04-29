package com.kbo.crawlerapi.domain;

import java.util.Arrays;

public enum GameStatus {
    SCHEDULED("scheduled"),
    LIVE("live"),
    FINAL("final"),
    POSTPONED("postponed"),
    CANCELLED("cancelled"),
    SUSPENDED("suspended"),
    UNKNOWN("unknown");

    private final String apiValue;

    GameStatus(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    public static GameStatus fromApiValue(String apiValue) {
        String normalized = apiValue == null ? null : apiValue.trim().toLowerCase().replace("-", "_");
        if ("in_progress".equals(normalized) || "inprogress".equals(normalized) || "running".equals(normalized) || "playing".equals(normalized)) {
            return LIVE;
        }
        if ("scheduled".equals(normalized) || "pre".equals(normalized) || "upcoming".equals(normalized)) {
            return SCHEDULED;
        }
        if ("completed".equals(normalized) || "ended".equals(normalized) || "game_over".equals(normalized) || "finished".equals(normalized)) {
            return FINAL;
        }
        return Arrays.stream(values())
                .filter(status -> status.apiValue.equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported game status: " + apiValue));
    }
}
