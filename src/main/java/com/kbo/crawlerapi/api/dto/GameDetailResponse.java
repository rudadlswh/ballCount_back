package com.kbo.crawlerapi.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record GameDetailResponse(
        String id,
        String provider,
        String providerGameId,
        LocalDate gameDate,
        OffsetDateTime scheduledAt,
        String stadium,
        String status,
        boolean isCancelled,
        boolean isPostponed,
        String cancelReason,
        TeamSummaryDto awayTeam,
        TeamSummaryDto homeTeam,
        Integer awayScore,
        Integer homeScore,
        GameStateDto state,
        String winningPitcher,
        String losingPitcher,
        String savePitcher,
        OffsetDateTime updatedAt,
        OffsetDateTime sourceUpdatedAt,
        boolean isStale
) {
}
