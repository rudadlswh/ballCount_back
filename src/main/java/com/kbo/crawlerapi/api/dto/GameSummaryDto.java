package com.kbo.crawlerapi.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record GameSummaryDto(
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
        OffsetDateTime updatedAt,
        OffsetDateTime sourceUpdatedAt,
        boolean isStale
) {
}
