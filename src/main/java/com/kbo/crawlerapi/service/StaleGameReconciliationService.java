package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class StaleGameReconciliationService {

    private final LiveGameSyncService liveGameSyncService;
    private final Set<LocalDate> inFlightDates = ConcurrentHashMap.newKeySet();

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

        List<StaleGameReconciliationDateResult> dateResults = new ArrayList<>();
        List<LiveGameSyncService.LiveSyncSummary> summaries = new ArrayList<>();
        for (LocalDate date : uniqueDates) {
            if (!inFlightDates.add(date)) {
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.SKIPPED_ALREADY_IN_PROGRESS, null, null));
                continue;
            }
            try {
                LiveGameSyncService.LiveSyncSummary summary = publicSummary(liveGameSyncService.sync(date, true));
                summaries.add(summary);
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.PROCESSED, null, summary));
            } catch (RuntimeException exception) {
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.FAILED, "refresh failed", null));
            } finally {
                inFlightDates.remove(date);
            }
        }
        int failedCount = summaries.stream()
                .mapToInt(LiveGameSyncService.LiveSyncSummary::failedCount)
                .sum() + (int) dateResults.stream()
                .filter(result -> result.status() == StaleGameReconciliationDateStatus.FAILED)
                .count();
        int skippedAlreadyInProgressCount = (int) dateResults.stream()
                .filter(result -> result.status() == StaleGameReconciliationDateStatus.SKIPPED_ALREADY_IN_PROGRESS)
                .count();
        return new StaleGameReconciliationResult(
                uniqueDates,
                summaries.size(),
                skippedAlreadyInProgressCount,
                failedCount,
                dateResults
        );
    }

    private LiveGameSyncService.LiveSyncSummary publicSummary(LiveGameSyncService.LiveSyncSummary summary) {
        return new LiveGameSyncService.LiveSyncSummary(
                summary.date(),
                summary.scannedCount(),
                summary.candidateCount(),
                summary.updatedCount(),
                summary.eventCreatedCount(),
                summary.notificationSentCount(),
                summary.notificationSkippedCount(),
                summary.failedCount(),
                summary.updatedGames(),
                List.of(),
                summary.errors().stream()
                        .map(error -> "refresh failed")
                        .toList()
        );
    }

    public record StaleGameReconciliationResult(
            List<LocalDate> dates,
            int processedDateCount,
            int skippedAlreadyInProgressCount,
            int failedCount,
            List<StaleGameReconciliationDateResult> dateResults
    ) {
    }

    public record StaleGameReconciliationDateResult(
            LocalDate date,
            StaleGameReconciliationDateStatus status,
            String errorMessage,
            LiveGameSyncService.LiveSyncSummary summary
    ) {
    }

    public enum StaleGameReconciliationDateStatus {
        PROCESSED,
        SKIPPED_ALREADY_IN_PROGRESS,
        FAILED
    }
}
