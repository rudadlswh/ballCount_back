package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TeamDto(
        UUID databaseId,
        String id,
        String name,
        String shortName,
        String englishName,
        String logoUrl,
        OffsetDateTime updatedAt
) {
}
