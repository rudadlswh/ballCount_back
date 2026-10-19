package com.kbo.crawlerapi.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AttendanceGameDto(
        UUID gameId,
        String publicGameId,
        LocalDate gameDate,
        OffsetDateTime scheduledAt,
        String stadium,
        String status,
        boolean isCancelled,
        boolean isPostponed,
        TeamSummaryDto awayTeam,
        TeamSummaryDto homeTeam,
        Integer awayScore,
        Integer homeScore
) {
}
