package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class StaleGameReconciliationService {

    private final LiveGameSyncService liveGameSyncService;
    private final Clock applicationClock;
    private final Set<LocalDate> inFlightDates = new HashSet<>();
    private static final int MAX_DATES = 3;
    private static final Duration COOLDOWN = Duration.ofSeconds(30);
    private Instant nextAdmission = Instant.MIN;

    public StaleGameReconciliationService(LiveGameSyncService liveGameSyncService, Clock applicationClock) {
        this.liveGameSyncService = liveGameSyncService;
        this.applicationClock = applicationClock;
    }

    public StaleGameReconciliationResult reconcile(List<LocalDate> dates) {
        LocalDate today = LocalDate.now(applicationClock);
        if (dates == null || dates.isEmpty() || dates.size() > MAX_DATES
                || dates.stream().anyMatch(date -> date == null || date.isAfter(today) || date.isBefore(today.minusDays(366)))) {
            throw new InvalidParameterException("dates must contain 1-3 dates within the last 366 days, including today");
        }
        List<LocalDate> uniqueDates = dates.stream()
                .distinct()
                .sorted()
                .toList();

        synchronized (inFlightDates) {
            if (inFlightDates.containsAll(uniqueDates)) {
                return new StaleGameReconciliationResult(uniqueDates, 0, uniqueDates.size(), 0,
                        uniqueDates.stream().map(date -> new StaleGameReconciliationDateResult(date,
                                StaleGameReconciliationDateStatus.SKIPPED_ALREADY_IN_PROGRESS, null, null)).toList());
            }
            if (!inFlightDates.isEmpty() || Instant.now(applicationClock).isBefore(nextAdmission)) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "reconciliation is limited to one request every 30 seconds");
            }
            nextAdmission = Instant.now(applicationClock).plus(COOLDOWN);
            inFlightDates.addAll(uniqueDates);
        }
        try {
            return reconcileDates(uniqueDates);
        } finally {
            synchronized (inFlightDates) {
                inFlightDates.clear();
            }
        }
    }

    private StaleGameReconciliationResult reconcileDates(List<LocalDate> uniqueDates) {
        List<StaleGameReconciliationDateResult> dateResults = new ArrayList<>();
        List<LiveGameSyncService.LiveSyncSummary> summaries = new ArrayList<>();
        for (LocalDate date : uniqueDates) {
            try {
                LiveGameSyncService.LiveSyncSummary summary = liveGameSyncService.sync(date, false);
                summaries.add(summary);
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.PROCESSED, null, summary));
            } catch (RuntimeException exception) {
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.FAILED, exception.getMessage(), null));
            }
        }
        int failedCount = summaries.stream()
                .mapToInt(LiveGameSyncService.LiveSyncSummary::failedCount)
                .sum() + (int) dateResults.stream()
                .filter(result -> result.status() == StaleGameReconciliationDateStatus.FAILED)
                .count();
        return new StaleGameReconciliationResult(
                uniqueDates,
                summaries.size(),
                0,
                failedCount,
                dateResults
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
