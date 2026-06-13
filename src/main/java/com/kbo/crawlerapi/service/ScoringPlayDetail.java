package com.kbo.crawlerapi.service;

public record ScoringPlayDetail(
        String batterName,
        String resultText,
        Integer hitBaseCount,
        Integer runsScored,
        Integer rbi,
        Integer inning,
        String inningHalf,
        String battingTeamId,
        String battingTeamName,
        Integer awayScoreAfter,
        Integer homeScoreAfter,
        String awayTeamName,
        String homeTeamName
) {
}
