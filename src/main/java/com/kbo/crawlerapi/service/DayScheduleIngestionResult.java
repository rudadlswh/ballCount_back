package com.kbo.crawlerapi.service;

import java.time.LocalDate;
import java.util.List;
import com.kbo.crawlerapi.parser.KboScheduleParser.SkippedScheduleRow;

public record DayScheduleIngestionResult(
        LocalDate date,
        int teamCreatedCount,
        int teamUpdatedCount,
        int gameProcessedCount,
        int gameCreatedCount,
        int gameUpdatedCount,
        int skippedRowCount,
        int skippedMissingProviderGameIdCount,
        int failureCount,
        List<SkippedScheduleRow> skippedRows
) {
}
