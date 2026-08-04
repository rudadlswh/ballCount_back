package com.kbo.crawlerapi.api.dto;

import java.util.List;
import java.util.UUID;

public record AttendanceListResponse(
        List<UUID> gameIds,
        List<AttendanceGameDto> records
) {
}
