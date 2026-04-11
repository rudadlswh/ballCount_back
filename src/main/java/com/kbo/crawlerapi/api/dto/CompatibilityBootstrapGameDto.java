package com.kbo.crawlerapi.api.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CompatibilityBootstrapGameDto(
        UUID id,
        String providerGameId,
        OffsetDateTime scheduledStart,
        String venue,
        String awayTeamId,
        String homeTeamId,
        Integer awayScore,
        Integer homeScore,
        String statusCode,
        String statusText,
        String seasonClassification,
        String inningText,
        CompatibilityBootstrapRunnerStateDto bases,
        Integer outs,
        String highlightText,
        List<CompatibilityBootstrapGameEventDto> events,
        String note
) {
}
