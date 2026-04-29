package com.kbo.crawlerapi.scheduler;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
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
    private static final Duration PRE_GAME_SYNC_INTERVAL = Duration.ofMinutes(30);
    private static final Set<String> LIVE_STATUS_VALUES = Set.of("LIVE", "IN_PROGRESS", "PLAYING", "ONGOING");

    private final LiveGameSyncService liveGameSyncService;
    private final LiveSyncProperties properties;
    private final GameRepository gameRepository;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Instant lastPreGameSyncAt;

    public LiveGameSyncScheduler(
            LiveGameSyncService liveGameSyncService,
            LiveSyncProperties properties,
            GameRepository gameRepository
    ) {
        this.liveGameSyncService = liveGameSyncService;
        this.properties = properties;
        this.gameRepository = gameRepository;
    }

    @Scheduled(fixedDelayString = "${app.live-sync.scheduler-interval:PT5S}")
    public void runTick() {
        log.info("[LiveGameSync] scheduler tick");

        if (!properties.isEnabled()) {
            log.info("[LiveGameSync] skipped disabled");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            log.warn("[LiveGameSync] skipped because previous run is active");
            return;
        }

        try {
            LocalDate todayKst = LocalDate.now(KST);
            List<Game> todaysGames = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(todayKst);
            boolean hasLiveGame = todaysGames.stream().anyMatch(this::isLiveGame);

            if (hasLiveGame) {
                log.info("[LiveGameSync] running live-game 10-second sync");
                liveGameSyncService.syncToday();
                return;
            }

            Instant now = Instant.now();
            if (lastPreGameSyncAt != null
                    && Duration.between(lastPreGameSyncAt, now).compareTo(PRE_GAME_SYNC_INTERVAL) < 0) {
                log.info("[LiveGameSync] skipped pre-game throttle active lastPreGameSyncAt={}", lastPreGameSyncAt);
                return;
            }

            log.info("[LiveGameSync] running pre-game 30-minute sync");
            liveGameSyncService.syncToday();
            lastPreGameSyncAt = now;
        } finally {
            running.set(false);
        }
    }

    private boolean isLiveGame(Game game) {
        if (game.getStatus() == GameStatus.LIVE || game.getStatus() == GameStatus.SUSPENDED) {
            return true;
        }
        if (isLiveStatusValue(game.getStatus() == null ? null : game.getStatus().name())) {
            return true;
        }
        if (isLiveStatusValue(game.getStatus() == null ? null : game.getStatus().getApiValue())) {
            return true;
        }
        return hasLiveInningState(game.getInningState());
    }

    private boolean isLiveStatusValue(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return false;
        }
        return LIVE_STATUS_VALUES.contains(rawStatus.trim().toUpperCase(Locale.ROOT));
    }

    private boolean hasLiveInningState(String inningState) {
        if (inningState == null || inningState.isBlank()) {
            return false;
        }
        String normalized = inningState.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("top") || normalized.startsWith("bottom");
    }
}