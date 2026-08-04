package com.kbo.crawlerapi.api.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

public record GameLineupResponse(
        String gameId,
        JsonNode away,
        JsonNode home,
        String rawHash,
        OffsetDateTime updatedAt,
        boolean isStale
) {
}
