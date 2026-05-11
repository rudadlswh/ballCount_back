package com.kbo.crawlerapi.api.dto;

public record GameBatterRecordDto(
        int sourceOrder,
        Integer battingOrder,
        String position,
        String playerName,
        Integer atBats,
        Integer runs,
        Integer hits,
        Integer rbi,
        Integer homeRuns,
        Integer walks,
        Integer strikeouts,
        Integer stolenBases,
        String battingAverage
) {
}
