package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.config.KboReconcileProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StaleGameReconciliationService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final LiveGameSyncService liveGameSyncService;
    private final KboReconcileProperties properties;
    private final Clock applicationClock;
    private final Set<LocalDate> inFlightDates = ConcurrentHashMap.newKeySet();
    private final Map<LocalDate, Instant> publicCooldownUntilByDate = new ConcurrentHashMap<>();

    @Autowired
    public StaleGameReconciliationService(
            LiveGameSyncService liveGameSyncService,
            KboReconcileProperties properties
    ) {
        this(liveGameSyncService, properties, Clock.system(KST));
    }

    public StaleGameReconciliationService(
            LiveGameSyncService liveGameSyncService,
            KboReconcileProperties properties,
            Clock applicationClock
    ) {
        this.liveGameSyncService = Objects.requireNonNull(liveGameSyncService, "liveGameSyncService must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.applicationClock = Objects.requireNonNull(applicationClock, "applicationClock must not be null");
    }

    public StaleGameReconciliationResult reconcilePublic(List<LocalDate> dates) {
        List<LocalDate> uniqueDates = normalizeDates(dates);
        validateMaxDates(uniqueDates, properties.getPublicMaxDates(), "dates");
        validatePublicDateRange(uniqueDates);
        return reconcileValidated(uniqueDates, true);
    }

    public StaleGameReconciliationResult reconcileAdmin(List<LocalDate> dates) {
        List<LocalDate> uniqueDates = normalizeDates(dates);
        validateMaxDates(uniqueDates, properties.getAdminMaxDates(), "dates");
        return reconcileValidated(uniqueDates, false);
    }

    public StaleGameReconciliationResult reconcile(List<LocalDate> dates) {
        return reconcileAdmin(dates);
    }

    private StaleGameReconciliationResult reconcileValidated(List<LocalDate> uniqueDates, boolean publicRequest) {
        if (uniqueDates.isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }

        cleanupExpiredPublicCooldowns();
        Instant now = Instant.now(applicationClock);
        List<StaleGameReconciliationDateResult> dateResults = new ArrayList<>();
        List<LiveGameSyncService.LiveSyncSummary> summaries = new ArrayList<>();
        for (LocalDate date : uniqueDates) {
            if (publicRequest) {
                Instant cooldownUntil = publicCooldownUntilByDate.get(date);
                if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
                    dateResults.add(new StaleGameReconciliationDateResult(
                            date,
                            StaleGameReconciliationDateStatus.SKIPPED_COOLDOWN,
                            "refresh recently requested",
                            null
                    ));
                    continue;
                }
            }
            if (!inFlightDates.add(date)) {
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.SKIPPED_ALREADY_IN_PROGRESS, null, null));
                continue;
            }
            boolean attempted = false;
            try {
                attempted = true;
                LiveGameSyncService.LiveSyncSummary summary = publicSummary(liveGameSyncService.sync(date, true));
                summaries.add(summary);
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.PROCESSED, null, summary));
            } catch (RuntimeException exception) {
                dateResults.add(new StaleGameReconciliationDateResult(date, StaleGameReconciliationDateStatus.FAILED, "refresh failed", null));
            } finally {
                inFlightDates.remove(date);
                if (publicRequest && attempted) {
                    publicCooldownUntilByDate.put(date, Instant.now(applicationClock).plus(publicCooldown()));
                }
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
        if (dates == null) {
            throw new InvalidParameterException("dates must not be empty");
        }
        List<LocalDate> uniqueDates = dates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (uniqueDates.isEmpty()) {
            throw new InvalidParameterException("dates must not be empty");
        }
        return uniqueDates;
    }

    private void validateMaxDates(List<LocalDate> uniqueDates, int maxDates, String fieldName) {
        if (maxDates > 0 && uniqueDates.size() > maxDates) {
            throw new InvalidParameterException(fieldName + " must contain at most " + maxDates + " dates");
        }
    }

    private void validatePublicDateRange(List<LocalDate> uniqueDates) {
        LocalDate today = LocalDate.now(applicationClock.withZone(KST));
        LocalDate earliestAllowedDate = today.minusDays(Math.max(0, properties.getPublicAllowedPastDays()));
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
        if (cooldown == null || cooldown.isNegative()) {
            return Duration.ZERO;
        }
        return cooldown;
    }

    private void cleanupExpiredPublicCooldowns() {
        Instant now = Instant.now(applicationClock);
        publicCooldownUntilByDate.entrySet().removeIf(entry -> !now.isBefore(entry.getValue()));
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
