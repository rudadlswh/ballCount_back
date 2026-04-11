package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;

public record ScoreboardGameDto(
        String id,
        String status,
        String cancelReason,
        OffsetDateTime scheduledAt,
        String stadium,
        TeamSummaryDto awayTeam,
        TeamSummaryDto homeTeam,
        Integer awayScore,
        Integer homeScore,
        GameStateDto state
) {
}
