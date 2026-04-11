package com.kbo.crawlerapi.api;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.service.DayScheduleIngestionResult;
import com.kbo.crawlerapi.service.KboScheduleImportService;

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
}
