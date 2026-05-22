package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class StaleGameReconciliationService {

    private final LiveGameSyncService liveGameSyncService;

    public StaleGameReconciliationService(LiveGameSyncService liveGameSyncService) {
        this.liveGameSyncService = liveGameSyncService;
    }

    public StaleGameReconciliationResult reconcile(List<LocalDate> dates) {
        List<LocalDate> uniqueDates = dates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (uniqueDates.isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }

        List<LiveGameSyncService.LiveSyncSummary> summaries = new ArrayList<>();
        for (LocalDate date : uniqueDates) {
            summaries.add(liveGameSyncService.sync(date, true));
        }
        int failedCount = summaries.stream()
                .mapToInt(LiveGameSyncService.LiveSyncSummary::failedCount)
                .sum();
        return new StaleGameReconciliationResult(uniqueDates, summaries.size(), failedCount, summaries);
    }

    public record StaleGameReconciliationResult(
            List<LocalDate> dates,
            int processedDateCount,
            int failedCount,
            List<LiveGameSyncService.LiveSyncSummary> summaries
    ) {
    }
}
