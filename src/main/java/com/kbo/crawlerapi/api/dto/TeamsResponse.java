package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record TeamsResponse(
        List<TeamDto> teams,
        OffsetDateTime updatedAt
) {
}
