package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.DateSyncLockService;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduleDiscoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ScheduleDiscoveryScheduler.class);
    private static final Duration REFRESH_INTERVAL = Duration.ofMinutes(30);
    private final KboScheduleImportService importer;
    private final DateSyncLockService locks;
    private final SyncProperties sync;
    private final Clock clock;
    private final GameRepository games;
    private Instant lastSucceededAt;
    private Instant nextCheckAt;
    private LocalDate lastSucceededDate;

    public ScheduleDiscoveryScheduler(KboScheduleImportService importer, DateSyncLockService locks,
                                      SyncProperties sync, Clock clock, GameRepository games) {
        this.importer = importer;
        this.locks = locks;
        this.sync = sync;
        this.clock = clock;
        this.games = games;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public void discover() {
        if (!sync.isActive(clock)) return;
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        YearMonth month = YearMonth.from(today);
        if (today.equals(lastSucceededDate)) {
            if (clock.instant().isBefore(nextCheckAt)) return;
            // Keep one daily discovery even on off days so an empty database can recover.
            if (!hasRemainingGames(today)) {
                nextCheckAt = clock.instant().plus(REFRESH_INTERVAL);
                return;
            }
        }
        try (var lock = locks.tryLock(today)) {
            if (!lock.acquired()) return;
            importer.importMonthlySchedule(month);
            importer.importMonthlySchedule(month.plusMonths(1));
            lastSucceededAt = clock.instant();
            nextCheckAt = lastSucceededAt.plus(REFRESH_INTERVAL);
            lastSucceededDate = today;
            log.info("[ScheduleDiscovery] refreshed month={} nextMonth={} lastSucceededAt={}", month, month.plusMonths(1), lastSucceededAt);
        } catch (RuntimeException exception) {
            log.warn("[ScheduleDiscovery] refresh failed month={} retryOnNextTick=true", month, exception);
        }
    }

    private boolean hasRemainingGames(LocalDate date) {
        return games.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(date).stream()
                .anyMatch(game -> game.getStatus() != GameStatus.CANCELLED
                        && game.getStatus() != GameStatus.POSTPONED
                        && game.getStatus() != GameStatus.SUSPENDED
                        && (game.getStatus() != GameStatus.FINAL || game.getFinalConfirmedAt() == null));
    }
}
