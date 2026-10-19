package com.kbo.crawlerapi.admin;

import com.kbo.crawlerapi.service.KboScheduleImportService;
import com.kbo.crawlerapi.service.ScheduleIngestionResult;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;

@Service
public class AdminScheduleImportService {

    static final int MAX_MONTH_COUNT = 3;

    private final KboScheduleImportService scheduleImportService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public AdminScheduleImportService(KboScheduleImportService scheduleImportService) {
        this.scheduleImportService = scheduleImportService;
    }

    public ScheduleImportRangeResult importRange(YearMonth fromMonth, YearMonth toMonth) {
        int monthCount = validateRange(fromMonth, toMonth);
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("다른 월간 일정 수집이 진행 중입니다.");
        }

        try {
            List<ScheduleImportMonthResult> results = new ArrayList<>(monthCount);
            for (YearMonth month = fromMonth; !month.isAfter(toMonth); month = month.plusMonths(1)) {
                results.add(importMonth(month));
            }
            return ScheduleImportRangeResult.from(fromMonth, toMonth, results);
        } finally {
            running.set(false);
        }
    }

    private ScheduleImportMonthResult importMonth(YearMonth month) {
        try {
            ScheduleIngestionResult result = scheduleImportService.importMonthlySchedule(month);
            return new ScheduleImportMonthResult(
                    month,
                    true,
                    result.gameCreatedCount(),
                    result.gameUpdatedCount(),
                    result.skippedRowCount(),
                    null
            );
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            return new ScheduleImportMonthResult(month, false, 0, 0, 0, message);
        }
    }

    private int validateRange(YearMonth fromMonth, YearMonth toMonth) {
        if (fromMonth == null || toMonth == null) {
            throw new IllegalArgumentException("시작 월과 종료 월을 입력해 주세요.");
        }
        if (fromMonth.isAfter(toMonth)) {
            throw new IllegalArgumentException("시작 월은 종료 월보다 늦을 수 없습니다.");
        }

        long monthCount = ChronoUnit.MONTHS.between(fromMonth, toMonth) + 1;
        if (monthCount > MAX_MONTH_COUNT) {
            throw new IllegalArgumentException("한 번에 최대 " + MAX_MONTH_COUNT + "개월까지 수집할 수 있습니다.");
        }
        return Math.toIntExact(monthCount);
    }

    public record ScheduleImportMonthResult(
            YearMonth month,
            boolean succeeded,
            int createdCount,
            int updatedCount,
            int skippedCount,
            String errorMessage
    ) {
    }

    public record ScheduleImportRangeResult(
            YearMonth fromMonth,
            YearMonth toMonth,
            int requestedMonthCount,
            int succeededMonthCount,
            int failedMonthCount,
            int createdCount,
            int updatedCount,
            int skippedCount,
            List<ScheduleImportMonthResult> months
    ) {
        private static ScheduleImportRangeResult from(
                YearMonth fromMonth,
                YearMonth toMonth,
                List<ScheduleImportMonthResult> months
        ) {
            int succeeded = (int) months.stream().filter(ScheduleImportMonthResult::succeeded).count();
            return new ScheduleImportRangeResult(
                    fromMonth,
                    toMonth,
                    months.size(),
                    succeeded,
                    months.size() - succeeded,
                    months.stream().mapToInt(ScheduleImportMonthResult::createdCount).sum(),
                    months.stream().mapToInt(ScheduleImportMonthResult::updatedCount).sum(),
                    months.stream().mapToInt(ScheduleImportMonthResult::skippedCount).sum(),
                    List.copyOf(months)
            );
        }
    }
}
