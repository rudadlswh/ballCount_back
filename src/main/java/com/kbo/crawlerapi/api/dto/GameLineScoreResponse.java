package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record GameLineScoreResponse(
        String gameId,
        List<LineScoreInningDto> innings,
        GameTotalsDto totals,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
