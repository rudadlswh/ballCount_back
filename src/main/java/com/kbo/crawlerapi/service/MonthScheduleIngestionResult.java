package com.kbo.crawlerapi.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record MonthScheduleIngestionResult(
        YearMonth yearMonth,
        LocalDate from,
        LocalDate to,
        int totalDays,
        int successDays,
        int failedDays,
        int createdCount,
        int updatedCount,
        int skippedCount,
        int failureCount,
        List<Failure> failures,
        List<DailyResult> dailyResults
) {

    public record Failure(
            LocalDate date,
            String message
    ) {
    }

    public record DailyResult(
            LocalDate date,
            int createdCount,
            int updatedCount,
            int skippedCount,
            int failureCount,
            DailyStatus status
    ) {
    }

    public enum DailyStatus {
        SUCCESS,
        FAILED
    }
}
