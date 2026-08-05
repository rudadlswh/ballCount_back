package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record StandingsResponse(
        int season,
        List<TeamStandingDto> standings,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
