package com.kbo.crawlerapi.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.DateTimeException;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.service.DayScheduleIngestionResult;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import com.kbo.crawlerapi.service.MonthScheduleIngestionResult;

@RestController
@RequestMapping("/admin/crawl")
public class AdminCrawlController {

    private final KboScheduleImportService kboScheduleImportService;

    public AdminCrawlController(KboScheduleImportService kboScheduleImportService) {
        this.kboScheduleImportService = kboScheduleImportService;
    }

    @PostMapping("/day")
    public DayCrawlResponse crawlDay(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(date);
        return new DayCrawlResponse(
                result.date(),
                result.teamCreatedCount(),
                result.teamUpdatedCount(),
                result.gameProcessedCount(),
                result.gameCreatedCount(),
                result.gameUpdatedCount(),
                result.skippedRowCount(),
                result.skippedMissingProviderGameIdCount(),
                result.failureCount()
        );
    }

    @PostMapping("/month")
    public MonthCrawlResponse crawlMonth(
            @RequestParam int year,
            @RequestParam int month
    ) {
        try {
            MonthScheduleIngestionResult result = kboScheduleImportService.crawlMonth(YearMonth.of(year, month));
            return new MonthCrawlResponse(
                    result.yearMonth().getYear(),
                    result.yearMonth().getMonthValue(),
                    result.from(),
                    result.to(),
                    result.totalDays(),
                    result.successDays(),
                    result.failedDays(),
                    result.createdCount(),
                    result.updatedCount(),
                    result.skippedCount(),
                    result.failureCount(),
                    result.failures().stream()
                            .map(failure -> new MonthCrawlFailureResponse(failure.date(), failure.message()))
                            .toList(),
                    result.dailyResults().stream()
                            .map(dailyResult -> new MonthCrawlDailyResponse(
                                    dailyResult.date(),
                                    dailyResult.createdCount(),
                                    dailyResult.updatedCount(),
                                    dailyResult.skippedCount(),
                                    dailyResult.failureCount(),
                                    dailyResult.status().name()
                            ))
                            .toList()
            );
        } catch (DateTimeException exception) {
            throw new InvalidParameterException("month must be in the range 1-12");
        }
    }

    public record DayCrawlResponse(
            LocalDate date,
            int teamCreatedCount,
            int teamUpdatedCount,
            int gameProcessedCount,
            int gameCreatedCount,
            int gameUpdatedCount,
            int skippedRowCount,
            int skippedMissingProviderGameIdCount,
            int failureCount
    ) {
    }

    public record MonthCrawlResponse(
            int year,
            int month,
            LocalDate from,
            LocalDate to,
            int totalDays,
            int successDays,
            int failedDays,
            int created,
            int updated,
            int skipped,
            int failed,
            List<MonthCrawlFailureResponse> failures,
            List<MonthCrawlDailyResponse> dailyResults
    ) {
    }

    public record MonthCrawlFailureResponse(
            LocalDate date,
            String message
    ) {
    }

    public record MonthCrawlDailyResponse(
            LocalDate date,
            int created,
            int updated,
            int skipped,
            int failed,
            String status
    ) {
    }
}
