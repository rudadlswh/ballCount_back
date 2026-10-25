package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.config.KboReconcileProperties;
import com.kbo.crawlerapi.config.SyncProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class StaleGameReconciliationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LiveGameSyncService liveGameSyncService;
    private final KboReconcileProperties properties;
    private final Clock applicationClock;
    private final SyncProperties syncProperties;
    private final Set<LocalDate> inFlightDates = new HashSet<>();
    private Instant nextPublicAdmission = Instant.MIN;

    public StaleGameReconciliationService(
            LiveGameSyncService liveGameSyncService,
            KboReconcileProperties properties,
            Clock applicationClock,
            SyncProperties syncProperties
    ) {
        this.liveGameSyncService = Objects.requireNonNull(liveGameSyncService, "liveGameSyncService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.applicationClock = Objects.requireNonNull(applicationClock, "applicationClock must not be null");
        this.syncProperties = Objects.requireNonNull(syncProperties, "syncProperties must not be null");
    }

    public StaleGameReconciliationResult reconcilePublic(List<LocalDate> dates) {
        validateMaxDates(dates, Math.max(1, Math.min(3, properties.getPublicMaxDates())), "dates");
        List<LocalDate> uniqueDates = normalizeDates(dates);
        validatePublicDateRange(uniqueDates);
        if (!syncProperties.isActive(applicationClock)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "automatic refresh is paused; stored game data remains available");
        }
        return reconcileValidated(uniqueDates, true);
    }

    public StaleGameReconciliationResult reconcileAdmin(List<LocalDate> dates) {
        validateMaxDates(dates, properties.getAdminMaxDates(), "dates");
        List<LocalDate> uniqueDates = normalizeDates(dates);
        return reconcileValidated(uniqueDates, false);
    }

    public StaleGameReconciliationResult reconcile(List<LocalDate> dates) {
        return reconcileAdmin(dates);
    }

    private StaleGameReconciliationResult reconcileValidated(List<LocalDate> uniqueDates, boolean publicRequest) {
        if (uniqueDates.isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }

        synchronized (inFlightDates) {
            if (inFlightDates.containsAll(uniqueDates)) {
                return new StaleGameReconciliationResult(uniqueDates, 0, uniqueDates.size(), 0,
                        uniqueDates.stream().map(date -> new StaleGameReconciliationDateResult(date,
                                StaleGameReconciliationDateStatus.SKIPPED_ALREADY_IN_PROGRESS, null, null)).toList());
            }
            if (!inFlightDates.isEmpty()
                    || (publicRequest && Instant.now(applicationClock).isBefore(nextPublicAdmission))) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "refresh rate limit exceeded");
            }
            if (publicRequest) {
                nextPublicAdmission = Instant.now(applicationClock).plus(publicCooldown());
            }
            inFlightDates.addAll(uniqueDates);
        }
        try {
            return reconcileDates(uniqueDates, publicRequest);
        } finally {
            synchronized (inFlightDates) {
                inFlightDates.clear();
            }
        }
    }

    private StaleGameReconciliationResult reconcileDates(List<LocalDate> uniqueDates, boolean publicRequest) {
        List<StaleGameReconciliationDateResult> dateResults = new ArrayList<>();
        List<LiveGameSyncService.LiveSyncSummary> summaries = new ArrayList<>();
        for (LocalDate date : uniqueDates) {
            try {
                LiveGameSyncService.LiveSyncSummary summary = publicSummary(liveGameSyncService.sync(date, !publicRequest));
                summaries.add(summary);
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.PROCESSED, null, summary));
            } catch (RuntimeException exception) {
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.FAILED, "refresh failed", null));
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

    private List<LocalDate> normalizeDates(List<LocalDate> dates) {
        if (dates == null || dates.stream().anyMatch(Objects::isNull)) {
            throw new InvalidParameterException("dates must not be empty");
        }
        List<LocalDate> uniqueDates = dates.stream()
                .distinct()
                .sorted()
                .toList();
        if (uniqueDates.isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }
        return uniqueDates;
    }

    private void validateMaxDates(List<LocalDate> uniqueDates, int maxDates, String fieldName) {
        if (uniqueDates != null && maxDates > 0 && uniqueDates.size() > maxDates) {
            throw new InvalidParameterException(fieldName + " must contain at most " + maxDates + " dates");
        }
    }

    private void validatePublicDateRange(List<LocalDate> uniqueDates) {
        LocalDate today = LocalDate.now(applicationClock.withZone(KST));
        LocalDate earliestAllowedDate = today.minusDays(Math.max(0, Math.min(366, properties.getPublicAllowedPastDays())));
        for (LocalDate date : uniqueDates) {
            if (date.isAfter(today)) {
                throw new InvalidParameterException("future dates are not allowed");
            }
            if (date.isBefore(earliestAllowedDate)) {
                throw new InvalidParameterException("dates must be on or after " + earliestAllowedDate);
            }
        }
    }

    private Duration publicCooldown() {
        Duration cooldown = properties.getPublicCooldown();
        return cooldown == null || cooldown.compareTo(Duration.ofSeconds(30)) < 0
                ? Duration.ofSeconds(30) : cooldown;
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
        SKIPPED_COOLDOWN,
        FAILED
    }
}
