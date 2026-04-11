package com.kbo.crawlerapi.api.dto;

public record GameTotalsDto(
        TeamTotalsDto away,
        TeamTotalsDto home
) {

    public record TeamTotalsDto(
            Integer runs,
            Integer hits,
            Integer errors,
            Integer balls
    ) {
    }
}
