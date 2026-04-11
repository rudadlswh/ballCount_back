package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record GamesByMonthResponse(
        int year,
        int month,
        List<GameSummaryDto> games,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
