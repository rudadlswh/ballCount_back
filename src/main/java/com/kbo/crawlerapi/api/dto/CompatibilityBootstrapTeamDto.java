package com.kbo.crawlerapi.api.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CompatibilityBootstrapTeamDto(
        String id,
        String name,
        String shortName,
        String englishName,
        String markText,
        Integer previousRegularSeasonRank
) {
}
