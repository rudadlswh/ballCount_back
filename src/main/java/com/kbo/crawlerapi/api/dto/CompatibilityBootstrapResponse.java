package com.kbo.crawlerapi.api.dto;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CompatibilityBootstrapResponse(
        List<CompatibilityBootstrapTeamDto> teams,
        List<CompatibilityBootstrapGameDto> games,
        List<CompatibilityBootstrapNotificationDto> notifications,
        Object settings
) {
}
