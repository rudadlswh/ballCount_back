package com.kbo.crawlerapi.service;

public record OnBasePlayDetail(
        String batterName,
        String resultText,
        Integer reachedBase,
        Integer inning,
        String inningHalf,
        String battingTeamId,
        String battingTeamName,
        Integer awayScore,
        Integer homeScore,
        String awayTeamName,
        String homeTeamName,
        String detailSource
) {
}
