package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CompatibilityBootstrapGameEventDto(
        UUID id,
        String type,
        String headline,
        String inningText,
        OffsetDateTime timestamp
) {
}
