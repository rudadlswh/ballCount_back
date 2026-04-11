package com.kbo.crawlerapi.api.dto;

public record GameStateDto(
        Integer inning,
        String half,
        String inningLabel,
        Integer balls,
        Integer strikes,
        Integer outs,
        BasesDto bases
) {

    public record BasesDto(
            Boolean first,
            Boolean second,
            Boolean third
    ) {
    }
}
