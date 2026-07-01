package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;

public record GameLiveStateResponse(
        String publicGameId,
        String status,
        Integer inning,
        String half,
        Integer awayScore,
        Integer homeScore,
        Integer balls,
        Integer strikes,
        Integer outs,
        BasesDto bases,
        String currentPitcherName,
        String currentBatterName,
        String rawHash,
        OffsetDateTime updatedAtKst
) {
    public record BasesDto(
            boolean first,
            boolean second,
            boolean third
    ) {
    }
}
