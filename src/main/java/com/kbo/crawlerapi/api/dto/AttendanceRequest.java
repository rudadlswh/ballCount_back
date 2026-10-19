package com.kbo.crawlerapi.api.dto;

public record AttendanceRequest(
        String installationId,
        String gameId
) {
}
