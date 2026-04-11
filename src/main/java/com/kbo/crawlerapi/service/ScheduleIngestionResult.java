package com.kbo.crawlerapi.service;

import java.time.YearMonth;
import java.util.List;
import com.kbo.crawlerapi.parser.KboScheduleParser.SkippedScheduleRow;

public record ScheduleIngestionResult(
        YearMonth yearMonth,
        int teamCreatedCount,
        int teamUpdatedCount,
        int gameCreatedCount,
        int gameUpdatedCount,
        int skippedRowCount,
        int skippedMissingProviderGameIdCount,
        int failureCount,
        List<SkippedScheduleRow> skippedRows
) {
}
