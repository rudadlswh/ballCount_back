package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LiveGameSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(LiveGameSyncScheduler.class);
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
    private final LiveSyncProperties properties;
    private final GameRepository gameRepository;
    private final Clock applicationClock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private LocalDateTime lastPreGameCheckSlot;

    public LiveGameSyncScheduler(
            LiveGameSyncService liveGameSyncService,
            LiveSyncProperties properties,
            GameRepository gameRepository,
            Clock applicationClock
    ) {
        this.liveGameSyncService = liveGameSyncService;
        this.properties = properties;
        this.gameRepository = gameRepository;
        this.applicationClock = applicationClock;
    }

    @Scheduled(fixedDelayString = "${app.live-sync.scheduler-interval:PT5S}")
    public void runTick() {
        if (!properties.isEnabled()) {
            log.info("[LiveGameSync] skipped disabled");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            log.info("[LiveGameSync] previous run still active");
            return;
        }

        try {
            LocalDate todayKst = LocalDate.now(applicationClock.withZone(KST));
            List<Game> todaysGames = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(todayKst);
            if (todaysGames.isEmpty()) {
                log.debug("[LiveGameSync] skipped no games today date={}", todayKst);
                return;
            }
            if (todaysGames.stream().allMatch(this::isTerminalGame)) {
                log.debug("[LiveGameSync] skipped all games terminal date={}", todayKst);
                return;
            }

            if (todaysGames.stream().anyMatch(this::isLiveGame)) {
                log.info("[LiveGameSync] running live-game sync interval={}", properties.getSchedulerInterval());
                liveGameSyncService.syncToday();
                return;
            }

            Optional<Instant> earliestNonTerminalStart = earliestNonTerminalScheduledStart(todaysGames);
            if (earliestNonTerminalStart.isEmpty()) {
                log.debug("[LiveGameSync] skipped no scheduled start for non-terminal games date={}", todayKst);
                return;
            }

            Instant now = applicationClock.instant();
            Instant scheduledAt = earliestNonTerminalStart.get();
            Instant eligibleFrom = scheduledAt.minus(properties.getPregameEligibilityWindow());
            long minutesUntilStart = Duration.between(now, scheduledAt).toMinutes();
            if (now.isBefore(eligibleFrom)) {
                log.debug(
                        "[LiveGameSync] skipped before pre-game eligibility scheduledAt={} now={} minutesUntilStart={} eligibleFrom={}",
                        scheduledAt,
                        now,
                        minutesUntilStart,
                        eligibleFrom
                );
                return;
            }
            if (now.isBefore(scheduledAt)) {
                log.debug(
                        "[LiveGameSync] pre-game eligible scheduledAt={} now={} minutesUntilStart={} eligibleFrom={}",
                        scheduledAt,
                        now,
                        minutesUntilStart,
                        eligibleFrom
                );
                runPreGameHalfHourCheckIfDue();
                return;
            }

            log.info("[LiveGameSync] running scheduled-start sync scheduledAt={} now={} minutesUntilStart={}",
                    scheduledAt,
                    now,
                    minutesUntilStart);
            liveGameSyncService.syncToday();
        } finally {
            running.set(false);
        }
    }

    private boolean isLiveGame(Game game) {
        if (game.getStatus() == GameStatus.LIVE) {
            return true;
        }
        if (isLiveStatusValue(game.getStatus() == null ? null : game.getStatus().name())) {
            return true;
        }
        if (isLiveStatusValue(game.getStatus() == null ? null : game.getStatus().getApiValue())) {
            return true;
        }
        return false;
    }

    private boolean isTerminalGame(Game game) {
        if (isTerminalStatusValue(game.getStatus() == null ? null : game.getStatus().name())) {
            return true;
        }
        return isTerminalStatusValue(game.getStatus() == null ? null : game.getStatus().getApiValue());
    }

    private Optional<Instant> earliestNonTerminalScheduledStart(List<Game> todaysGames) {
        return todaysGames.stream()
                .filter(game -> !isTerminalGame(game))
                .map(Game::getScheduledAt)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.comparing(OffsetDateTime::toInstant))
                .map(OffsetDateTime::toInstant);
    }

//    private void runPreGameHalfHourCheckIfDue() {
//        LocalDateTime nowKst = LocalDateTime.now(applicationClock.withZone(KST));
//        if (nowKst.getMinute() != 0 && nowKst.getMinute() != 30) {
//            log.debug("[LiveGameSync] skipped pre-game not half-hour slot now_kst={}", nowKst);
//            return;
//        }
//
//        LocalDateTime currentSlot = nowKst.truncatedTo(ChronoUnit.MINUTES);
//        if (currentSlot.equals(lastPreGameCheckSlot)) {
//            log.debug("[LiveGameSync] skipped pre-game half-hour slot already checked slot={}", currentSlot);
//            return;
//        }
//
//        log.info("[LiveGameSync] running pre-game half-hour check");
//        liveGameSyncService.syncToday();
//        lastPreGameCheckSlot = currentSlot;
//    }

    private void runPreGameHalfHourCheckIfDue() {
        LocalDateTime nowKst = LocalDateTime.now(applicationClock.withZone(KST));
        LocalDateTime currentSlot = nowKst.truncatedTo(ChronoUnit.MINUTES);

        Duration interval = properties.getPregameCheckInterval();
        if (interval == null || interval.isZero() || interval.isNegative()) {
            interval = Duration.ofMinutes(30);
        }

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

        log.info("[LiveGameSync] running pre-game check interval={} now_kst={}", interval, nowKst);
        liveGameSyncService.syncToday();
        lastPreGameCheckSlot = currentSlot;
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
}
