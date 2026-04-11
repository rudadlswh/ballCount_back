package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CompatibilityBootstrapNotificationDto(
        UUID id,
        String type,
        String title,
        String body,
        OffsetDateTime sentAt,
        boolean isRead,
        UUID relatedGameId,
        List<String> relatedTeamIds
) {
}
