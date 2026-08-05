package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One app-facing, corrected snapshot of every section used by the game detail screen.
 * Existing section endpoints remain available for backwards compatibility.
 */
public record GameDetailDataResponse(
        GameDetailResponse detail,
        GameLiveStateResponse liveState,
        GameLineScoreResponse lineScore,
        GameBoxscoreResponse boxscore,
        GameLineupResponse lineup,
        List<String> appliedFallbacks,
        List<String> unavailableSections,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
