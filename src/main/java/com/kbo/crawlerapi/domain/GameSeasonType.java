package com.kbo.crawlerapi.domain;

public enum GameSeasonType {
    UNKNOWN("unknown"),
    REGULAR_SEASON("regular_season"),
    EXHIBITION_PRESEASON("exhibition_preseason"),
    POSTSEASON("postseason"),
    ALL_STAR("all_star");

    private final String apiValue;

    GameSeasonType(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() { return apiValue; }

    public static GameSeasonType fromSeriesId(Integer seriesId) {
        if (seriesId == null) return UNKNOWN;
        return switch (seriesId) {
            case 0 -> REGULAR_SEASON;
            case 9 -> EXHIBITION_PRESEASON;
            case 3, 4, 5, 7 -> POSTSEASON;
            case 6 -> ALL_STAR;
            default -> UNKNOWN;
        };
    }
}
