package com.kbo.crawlerapi.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LiveGameSyncSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate GAME_DATE = LocalDate.of(2026, 4, 30);

    @Test
    void skipsWhenLiveSyncIsDisabled() {
        LiveSyncProperties properties = properties(false);
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        GameRepository repository = mock(GameRepository.class);
        LiveGameSyncScheduler scheduler = scheduler(service, properties, repository, clockAt("2026-04-30T12:00:00+09:00"));

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isZero();
        verify(repository, never()).findByGameDateOrderByScheduledAtAscPublicGameIdAsc(any(LocalDate.class));
    }

    @Test
    void runsWhenLiveSyncIsEnabled() {
        LiveSyncProperties properties = properties(true);
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        GameRepository repository = gameRepository(List.of(game(GameStatus.LIVE, "2026-04-30T18:30:00+09:00")));
        LiveGameSyncScheduler scheduler = scheduler(service, properties, repository, clockAt("2026-04-30T12:01:00+09:00"));

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void noGamesTodayDoesNotCallSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of()),
                clockAt("2026-04-30T12:00:00+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isZero();
    }

    @Test
    void allTerminalGamesDoNotCallSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(
                        game(GameStatus.FINAL, "2026-04-30T18:30:00+09:00"),
                        game(GameStatus.CANCELLED, "2026-04-30T18:30:00+09:00"),
                        game(GameStatus.POSTPONED, "2026-04-30T18:30:00+09:00"),
                        game(GameStatus.SUSPENDED, "2026-04-30T18:30:00+09:00")
                )),
                clockAt("2026-04-30T20:00:00+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isZero();
    }

    @Test
    void beforeFirstScheduledStartOutsideHalfHourSlotDoesNotCallSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T12:01:00+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isZero();
    }

    @Test
    void beforeFirstScheduledStartAtZeroMinuteCallsSyncTodayOnce() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T12:00:03+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void beforeFirstScheduledStartAtThirtyMinuteCallsSyncTodayOnce() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T12:30:03+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void repeatedTicksWithinSameHalfHourMinuteCallSyncTodayOnlyOnce() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T12:00:03+09:00")
        );

        scheduler.runTick();
        scheduler.runTick();
        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void atOrAfterScheduledStartTimeCallsSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T18:30:00+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void liveGameStatusCallsSyncTodayAndBypassesHalfHourRule() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.LIVE, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T12:01:00+09:00")
        );

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
    }

    @Test
    void previousRunActiveDoesNotCallSyncTodayAgain() throws Exception {
        BlockingLiveGameSyncService service = new BlockingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(true),
                gameRepository(List.of(game(GameStatus.LIVE, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T12:01:00+09:00")
        );

        Thread firstRun = new Thread(scheduler::runTick);
        firstRun.start();
        assertThat(service.started.await(2, TimeUnit.SECONDS)).isTrue();

        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        service.release.countDown();
        firstRun.join(2_000);
        assertThat(firstRun.isAlive()).isFalse();
    }

    private static LiveGameSyncScheduler scheduler(
            LiveGameSyncService service,
            LiveSyncProperties properties,
            GameRepository repository,
            Clock clock
    ) {
        return new LiveGameSyncScheduler(service, properties, repository, clock);
    }

    private static LiveSyncProperties properties(boolean enabled) {
        LiveSyncProperties properties = new LiveSyncProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private static Clock clockAt(String offsetDateTime) {
        return Clock.fixed(OffsetDateTime.parse(offsetDateTime).toInstant(), KST);
    }

    private static GameRepository gameRepository(List<Game> games) {
        GameRepository repository = mock(GameRepository.class);
        when(repository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(any(LocalDate.class))).thenReturn(games);
        return repository;
    }

    private static Game game(GameStatus status, String scheduledAt) {
        Team homeTeam = new Team(
                UUID.randomUUID(),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                null
        );
        Team awayTeam = new Team(
                UUID.randomUUID(),
                "kia",
                "KIA Tigers",
                "KIA",
                "KIA Tigers",
                null
        );
        return new Game(
                UUID.randomUUID(),
                UUID.randomUUID().toString(),
                "kbo",
                UUID.randomUUID().toString(),
                GAME_DATE,
                OffsetDateTime.parse(scheduledAt),
                "잠실",
                status,
                homeTeam,
                awayTeam,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private static class RecordingLiveGameSyncService extends LiveGameSyncService {

        protected final AtomicInteger invocationCount = new AtomicInteger();

        private RecordingLiveGameSyncService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public LiveSyncSummary syncToday() {
            invocationCount.incrementAndGet();
            return null;
        }
    }

    private static final class BlockingLiveGameSyncService extends RecordingLiveGameSyncService {

        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public LiveSyncSummary syncToday() {
            invocationCount.incrementAndGet();
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }
}
