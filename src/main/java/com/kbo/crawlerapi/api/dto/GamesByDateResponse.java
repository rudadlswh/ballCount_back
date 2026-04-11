package com.kbo.crawlerapi.api.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record GamesByDateResponse(
        LocalDate date,
        List<GameSummaryDto> games,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
