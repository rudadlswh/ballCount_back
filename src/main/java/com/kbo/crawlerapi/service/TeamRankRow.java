package com.kbo.crawlerapi.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TeamRankRow(
        int season,
        UUID teamId,
        int rank,
        String teamName,
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
