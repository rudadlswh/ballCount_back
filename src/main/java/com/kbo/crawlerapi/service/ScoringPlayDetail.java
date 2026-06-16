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
        String homeTeamName,
        String selectedEventType,
        String selectedEventText,
        Integer runScoredEventCount
) {
    public ScoringPlayDetail(
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
        this(
                batterName,
                resultText,
                hitBaseCount,
                runsScored,
                rbi,
                inning,
                inningHalf,
                battingTeamId,
                battingTeamName,
                awayScoreAfter,
                homeScoreAfter,
                awayTeamName,
                homeTeamName,
                null,
                null,
                null
        );
    }
}
