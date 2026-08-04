package com.kbo.crawlerapi.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AttendanceGameRecordRow(
        UUID gameId,
        String publicGameId,
        LocalDate gameDate,
        OffsetDateTime scheduledAt,
        String stadium,
        String status,
        boolean cancelled,
        boolean postponed,
        String awayTeamId,
        String awayTeamName,
        String awayTeamShortName,
        String awayTeamLogoUrl,
        String homeTeamId,
        String homeTeamName,
        String homeTeamShortName,
        String homeTeamLogoUrl,
        Integer awayScore,
        Integer homeScore
) {
}
