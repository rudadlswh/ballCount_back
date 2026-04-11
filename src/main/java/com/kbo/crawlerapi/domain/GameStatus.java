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
        return Arrays.stream(values())
                .filter(status -> status.apiValue.equals(apiValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported game status: " + apiValue));
    }
}
