package com.kbo.crawlerapi.domain;

import java.util.Arrays;

public enum GameCancelReason {
    RAIN("rain"),
    GROUND("ground"),
    ETC("etc"),
    UNKNOWN("unknown");

    private final String apiValue;

    GameCancelReason(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }

    public static GameCancelReason fromApiValue(String apiValue) {
        return Arrays.stream(values())
                .filter(reason -> reason.apiValue.equals(apiValue))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported cancel reason: " + apiValue));
    }
}
