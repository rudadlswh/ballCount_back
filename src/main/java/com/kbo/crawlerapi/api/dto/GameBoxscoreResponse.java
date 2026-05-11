package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record GameBoxscoreResponse(
        String gameId,
        List<GameBatterRecordDto> awayBatters,
        List<GameBatterRecordDto> homeBatters,
        List<GamePitcherRecordDto> awayPitchers,
        List<GamePitcherRecordDto> homePitchers,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
