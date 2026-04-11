package com.kbo.crawlerapi.api;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.kbo.crawlerapi.parser.KboScheduleParser.SkippedScheduleRow;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import com.kbo.crawlerapi.service.ScheduleIngestionResult;
import io.swagger.v3.oas.annotations.Hidden;

@RestController
@RequestMapping("/internal")
@Hidden
public class InternalScheduleImportController {

    private final KboScheduleImportService kboScheduleImportService;

    public InternalScheduleImportController(KboScheduleImportService kboScheduleImportService) {
        this.kboScheduleImportService = kboScheduleImportService;
    }

    @PostMapping("/schedule/import")
    public ScheduleImportResponse importMonthlySchedule(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month
    ) {
        YearMonth targetYearMonth = year != null && month != null
                ? YearMonth.of(year, month)
                : YearMonth.now(ZoneId.of("Asia/Seoul"));

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(targetYearMonth);
        return new ScheduleImportResponse(
                result.yearMonth().getYear(),
                result.yearMonth().getMonthValue(),
                result.teamCreatedCount(),
                result.teamUpdatedCount(),
                result.gameCreatedCount(),
                result.gameUpdatedCount(),
                result.skippedRowCount(),
                result.skippedMissingProviderGameIdCount(),
                result.failureCount(),
                result.skippedRows()
        );
    }

    public record ScheduleImportResponse(
            int year,
            int month,
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
}
