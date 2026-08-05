package com.kbo.crawlerapi.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TeamStandingDto(
        int season,
        UUID teamDatabaseId,
        String teamId,
        String teamName,
        int rank,
        Integer previousRank,
        int gamesPlayed,
        int wins,
        int losses,
        int draws,
        BigDecimal winningPercentage,
        BigDecimal gamesBehind,
        String streakType,
        int streakCount,
        String streakText,
        LocalDate lastGameDate,
        OffsetDateTime calculatedAt,
        OffsetDateTime updatedAt
) {
}
