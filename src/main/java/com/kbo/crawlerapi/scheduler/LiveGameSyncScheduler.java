package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class LiveGameSyncScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(LiveGameSyncScheduler.class);
    private static final String JOB_NAME = "live-game-sync";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Set<String> LIVE_STATUS_VALUES = Set.of("LIVE", "IN_PROGRESS", "PLAYING", "ONGOING");
    private static final Set<String> TERMINAL_STATUS_VALUES = Set.of(
            "FINAL",
            "COMPLETED",
            "CANCELLED",
            "CANCELED",
            "POSTPONED",
            "SUSPENDED"
    );

    private final LiveGameSyncService liveGameSyncService;
    private final SyncProperties syncProperties;
    private final LiveSyncProperties properties;
    private final GameRepository gameRepository;
    private final Clock applicationClock;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LocalDateTime lastPreGameCheckSlot;
    private volatile boolean lifecycleRunning;
    private volatile ScheduledFuture<?> scheduledFuture;
    private String lastLoggedState;
    private Instant lastStateLogAt;

    @Autowired
    public LiveGameSyncScheduler(
            LiveGameSyncService liveGameSyncService,
            SyncProperties syncProperties,
            LiveSyncProperties properties,
            GameRepository gameRepository,
            Clock applicationClock,
            TaskScheduler taskScheduler
    ) {
        this.liveGameSyncService = liveGameSyncService;
        this.syncProperties = syncProperties;
        this.properties = properties;
        this.gameRepository = gameRepository;
        this.applicationClock = applicationClock;
        this.taskScheduler = taskScheduler;
    }

    LiveGameSyncScheduler(
            LiveGameSyncService liveGameSyncService,
            SyncProperties syncProperties,
            LiveSyncProperties properties,
            GameRepository gameRepository,
            Clock applicationClock
    ) {
        this(liveGameSyncService, syncProperties, properties, gameRepository, applicationClock, null);
    }

    public void runTick() {
        runTickAndGetNextDelay();
    }

    Duration runTickAndGetNextDelay() {
        if (!syncProperties.isEnabled()) {
            return logAndReturn(TickOutcome.skip("disabled", normalized(properties.getIdleInterval()), "live sync disabled"));
        }

        if (!running.compareAndSet(false, true)) {
            return logAndReturn(TickOutcome.skip("busy", normalized(properties.getLivePollingInterval()), "previous run still active"));
        }

        try {
            LocalDate todayKst = LocalDate.now(applicationClock.withZone(KST));
            List<Game> todaysGames = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(todayKst);
            if (todaysGames.isEmpty()) {
                return logAndReturn(TickOutcome.skip("idle:no-games", normalized(properties.getIdleInterval()), "no games today date=" + todayKst));
            }
            if (todaysGames.stream().anyMatch(this::isLiveGame)) {
                liveGameSyncService.syncToday();
                return logAndReturn(TickOutcome.sync("live", normalized(properties.getLivePollingInterval()), "live game candidate present date=" + todayKst));
            }
            if (todaysGames.stream().anyMatch(this::needsFinalConfirmation)) {
                liveGameSyncService.syncToday();
                return logAndReturn(TickOutcome.sync(
                        "post-final",
                        normalized(properties.getFinalConfirmationPollingInterval()),
                        "final confirmation candidate present date=" + todayKst
                ));
            }
            if (todaysGames.stream().allMatch(this::isTerminalGame)) {
                return logAndReturn(TickOutcome.skip("idle:all-terminal", normalized(properties.getIdleInterval()), "all games terminal date=" + todayKst));
            }

            Optional<Instant> earliestNonTerminalStart = earliestNonTerminalScheduledStart(todaysGames);
            if (earliestNonTerminalStart.isEmpty()) {
                return logAndReturn(TickOutcome.skip(
                        "idle:no-scheduled-start",
                        normalized(properties.getIdleInterval()),
                        "no scheduled start for non-terminal games date=" + todayKst
                ));
            }

            Instant now = applicationClock.instant();
            Instant scheduledAt = earliestNonTerminalStart.get();
            Instant eligibleFrom = scheduledAt.minus(properties.getPregameEligibilityWindow());
            long minutesUntilStart = Duration.between(now, scheduledAt).toMinutes();
            if (now.isBefore(eligibleFrom)) {
                Duration nextDelay = minPositive(normalized(properties.getIdleInterval()), Duration.between(now, eligibleFrom));
                return logAndReturn(TickOutcome.skip(
                        "idle:before-pregame",
                        nextDelay,
                        "scheduledAt=" + scheduledAt + " minutesUntilStart=" + minutesUntilStart + " eligibleFrom=" + eligibleFrom
                ));
            }
            if (now.isBefore(scheduledAt)) {
                Duration pregameInterval = pregameInterval(now, scheduledAt);
                runPreGameCheckIfDue(pregameInterval);
                return logAndReturn(TickOutcome.sync(
                        "pregame",
                        pregameInterval,
                        "scheduledAt=" + scheduledAt + " minutesUntilStart=" + minutesUntilStart
                ));
            }

            liveGameSyncService.syncToday();
            return logAndReturn(TickOutcome.sync(
                    "scheduled-start",
                    normalized(properties.getLivePollingInterval()),
                    "scheduledAt=" + scheduledAt + " minutesUntilStart=" + minutesUntilStart
            ));
        } finally {
            running.set(false);
        }
    }

    @Override
    public void start() {
        if (taskScheduler == null || lifecycleRunning || !syncProperties.isEnabled()) {
            return;
        }
        lifecycleRunning = true;
        scheduleNext(Duration.ZERO);
    }

    @Override
    public void stop() {
        lifecycleRunning = false;
        ScheduledFuture<?> future = scheduledFuture;
        if (future != null) {
            future.cancel(false);
        }
    }

    @Override
    public boolean isRunning() {
        return lifecycleRunning;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    private void runScheduledTick() {
        Duration nextDelay = normalized(properties.getRetryInterval());
        RuntimeException failure = null;
        try {
            nextDelay = runTickAndGetNextDelay();
        } catch (RuntimeException exception) {
            failure = exception;
        } finally {
            boolean nextExecutionScheduled = false;
            try {
                nextExecutionScheduled = scheduleNext(nextDelay);
            } finally {
                if (failure != null) {
                    log.error(
                            "[LiveGameSync] scheduled tick failed job={} executionDate={} exceptionType={} retryDelay={} nextExecutionScheduled={}",
                            JOB_NAME,
                            LocalDate.now(applicationClock.withZone(KST)),
                            failure.getClass().getName(),
                            nextDelay,
                            nextExecutionScheduled,
                            failure
                    );
                }
            }
        }
    }

    private boolean scheduleNext(Duration delay) {
        if (!lifecycleRunning || taskScheduler == null) {
            return false;
        }
        Duration safeDelay = delay == null || delay.isNegative() ? Duration.ZERO : delay;
        scheduledFuture = taskScheduler.schedule(this::runScheduledTick, Instant.now().plus(safeDelay));
        return scheduledFuture != null;
    }

    private boolean isLiveGame(Game game) {
        if (game.getStatus() == GameStatus.LIVE) {
            return true;
        }
        if (isLiveStatusValue(game.getStatus() == null ? null : game.getStatus().name())) {
            return true;
        }
        return isLiveStatusValue(game.getStatus() == null ? null : game.getStatus().getApiValue());
    }

    private boolean isTerminalGame(Game game) {
        if (needsFinalConfirmation(game)) {
            return false;
        }
        if (isTerminalStatusValue(game.getStatus() == null ? null : game.getStatus().name())) {
            return true;
        }
        return isTerminalStatusValue(game.getStatus() == null ? null : game.getStatus().getApiValue());
    }

    private boolean needsFinalConfirmation(Game game) {
        return game.getStatus() == GameStatus.FINAL && game.getFinalConfirmedAt() == null;
    }

    private Optional<Instant> earliestNonTerminalScheduledStart(List<Game> todaysGames) {
        return todaysGames.stream()
                .filter(game -> !isTerminalGame(game))
                .map(Game::getScheduledAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparing(OffsetDateTime::toInstant))
                .map(OffsetDateTime::toInstant);
    }

    private void runPreGameCheckIfDue(Duration interval) {
        LocalDateTime nowKst = LocalDateTime.now(applicationClock.withZone(KST));
        LocalDateTime currentSlot = nowKst.truncatedTo(ChronoUnit.MINUTES);

        interval = normalized(interval);
        if (currentSlot.equals(lastPreGameCheckSlot)) {
            log.debug("[LiveGameSync] skipped pre-game slot already checked slot={}", currentSlot);
            return;
        }

        if (lastPreGameCheckSlot != null) {
            Duration elapsed = Duration.between(lastPreGameCheckSlot, currentSlot);
            if (elapsed.compareTo(interval) < 0) {
                log.debug(
                        "[LiveGameSync] skipped pre-game check interval not reached now_kst={} last_slot={} interval={} elapsed={}",
                        nowKst,
                        lastPreGameCheckSlot,
                        interval,
                        elapsed);
                return;
            }
        }

        liveGameSyncService.syncToday();
        lastPreGameCheckSlot = currentSlot;
    }

    private Duration pregameInterval(Instant now, Instant scheduledAt) {
        Duration untilStart = Duration.between(now, scheduledAt);
        if (untilStart.compareTo(normalized(properties.getPregameFastPollingWindow())) <= 0) {
            return normalized(properties.getPregameFastPollingInterval());
        }
        return normalized(properties.getPregameCheckInterval());
    }

    private Duration logAndReturn(TickOutcome outcome) {
        logState(outcome);
        return outcome.nextDelay();
    }

    private void logState(TickOutcome outcome) {
        Instant now = applicationClock.instant();
        String state = outcome.state();
        boolean stateChanged = !state.equals(lastLoggedState);
        boolean summaryDue = lastStateLogAt == null
                || Duration.between(lastStateLogAt, now).compareTo(normalized(properties.getIdleInterval())) >= 0;
        if (stateChanged || summaryDue) {
            log.info(
                    "[LiveGameSync] state={} action={} nextDelay={} detail={}",
                    outcome.state(),
                    outcome.syncRan() ? "sync" : "skip",
                    outcome.nextDelay(),
                    outcome.detail()
            );
            lastLoggedState = state;
            lastStateLogAt = now;
        } else {
            log.debug(
                    "[LiveGameSync] state={} action={} nextDelay={} detail={}",
                    outcome.state(),
                    outcome.syncRan() ? "sync" : "skip",
                    outcome.nextDelay(),
                    outcome.detail()
            );
        }
    }

    private Duration normalized(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Duration.ofSeconds(1);
        }
        return duration;
    }

    private Duration minPositive(Duration first, Duration second) {
        Duration safeFirst = normalized(first);
        if (second == null || second.isZero() || second.isNegative()) {
            return safeFirst;
        }
        return second.compareTo(safeFirst) < 0 ? second : safeFirst;
    }

    private boolean isLiveStatusValue(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return false;
        }
        return LIVE_STATUS_VALUES.contains(rawStatus.trim().toUpperCase(Locale.ROOT));
    }

    private boolean isTerminalStatusValue(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return false;
        }
        return TERMINAL_STATUS_VALUES.contains(rawStatus.trim().toUpperCase(Locale.ROOT));
    }

    record TickOutcome(String state, Duration nextDelay, boolean syncRan, String detail) {
        static TickOutcome sync(String state, Duration nextDelay, String detail) {
            return new TickOutcome(state, nextDelay, true, detail);
        }

        static TickOutcome skip(String state, Duration nextDelay, String detail) {
            return new TickOutcome(state, nextDelay, false, detail);
        }
    }
}
