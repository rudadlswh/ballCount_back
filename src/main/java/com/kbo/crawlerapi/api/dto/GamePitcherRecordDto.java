package com.kbo.crawlerapi.api.dto;

public record GamePitcherRecordDto(
        int sourceOrder,
        Integer pitchingOrder,
        String playerName,
        String appearance,
        String decisionResult,
        Integer wins,
        Integer losses,
        Integer saves,
        String inningsPitched,
        Integer battersFaced,
        Integer pitchCount,
        Integer atBats,
        Integer hits,
        Integer homeRuns,
        Integer walksOrHitByPitch,
        Integer strikeouts,
        Integer runs,
        Integer earnedRuns,
        String era
) {
}
