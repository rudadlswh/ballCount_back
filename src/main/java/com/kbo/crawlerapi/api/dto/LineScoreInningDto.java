package com.kbo.crawlerapi.api.dto;

public record LineScoreInningDto(
        int inning,
        Integer awayRuns,
        Integer homeRuns
) {
}
