package com.kbo.crawlerapi.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record GameDetailImportResult(
        String gameId,
        String providerGameId,
        LocalDate gameDate,
        String status,
        boolean snapshotCreated,
        boolean lineScoresUpdated,
        int lineScoreCount,
        Integer awayScore,
        Integer homeScore,
        Integer inning,
        String inningHalf,
        String inningLabel,
        OffsetDateTime sourceUpdatedAt,
        OffsetDateTime fetchedAt
) {
}
