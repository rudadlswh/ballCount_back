package com.kbo.crawlerapi.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record ScoreboardResponse(
        LocalDate date,
        List<ScoreboardGameDto> games,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
