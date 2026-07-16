package com.kbo.crawlerapi.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.TaskScheduler;

@ExtendWith(OutputCaptureExtension.class)
class LiveGameSyncSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDate GAME_DATE = LocalDate.of(2026, 4, 30);

    @Test
    void skipsWhenLiveSyncIsDisabled() {
        LiveSyncProperties properties = properties();
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        GameRepository repository = mock(GameRepository.class);
        LiveGameSyncScheduler scheduler = scheduler(service, properties, repository, clockAt("2026-04-30T12:00:00+09:00"), false);

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isZero();
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(30));
        verify(repository, never()).findByGameDateOrderByScheduledAtAscPublicGameIdAsc(any(LocalDate.class));
    }

    @Test
    void runsWhenLiveSyncIsEnabled() {
        LiveSyncProperties properties = properties();
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        GameRepository repository = gameRepository(List.of(game(GameStatus.LIVE, "2026-04-30T18:30:00+09:00")));
        LiveGameSyncScheduler scheduler = scheduler(service, properties, repository, clockAt("2026-04-30T12:01:00+09:00"));

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void noGamesTodayDoesNotCallSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of()),
                clockAt("2026-04-30T12:00:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isZero();
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void allTerminalGamesDoNotCallSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(
                        finalConfirmedGame("2026-04-30T18:30:00+09:00"),
                        game(GameStatus.CANCELLED, "2026-04-30T18:30:00+09:00"),
                        game(GameStatus.POSTPONED, "2026-04-30T18:30:00+09:00"),
                        game(GameStatus.SUSPENDED, "2026-04-30T18:30:00+09:00")
                )),
                clockAt("2026-04-30T20:00:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isZero();
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void unconfirmedFinalGameUsesPostFinalInterval() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.FINAL, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T21:00:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void beforeFirstScheduledStartInsidePregameWindowCallsSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:00:00+09:00"))),
                clockAt("2026-04-30T12:01:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void fourHoursAndOneMinuteBeforeScheduledStartDoesNotCallSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:01:00+09:00"))),
                clockAt("2026-04-30T12:00:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isZero();
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void beforeFirstScheduledStartAtZeroMinuteCallsSyncTodayOnce() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:00:00+09:00"))),
                clockAt("2026-04-30T12:00:03+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void beforeFirstScheduledStartAtThirtyMinuteCallsSyncTodayOnce() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:30:00+09:00"))),
                clockAt("2026-04-30T12:30:03+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void threeHoursAndFiftyNineMinutesBeforeScheduledStartCallsSyncTodayWhenHalfHourSlotIsDue() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:59:00+09:00"))),
                clockAt("2026-04-30T13:00:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void thirtyOneMinutesBeforeScheduledStartUsesPregameInterval() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T17:59:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void thirtyMinutesBeforeScheduledStartUsesFastPregameInterval() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T18:00:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void repeatedTicksWithinSameHalfHourMinuteCallSyncTodayOnlyOnce() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:00:00+09:00"))),
                clockAt("2026-04-30T12:00:03+09:00")
        );

        scheduler.runTick();
        scheduler.runTick();
        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void preGameChecksCanRunRepeatedlyAtConfiguredInterval() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveSyncProperties properties = properties();
        properties.setPregameCheckInterval(Duration.ofMinutes(1));
        MutableClock clock = new MutableClock("2026-04-30T12:00:03+09:00");
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties,
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T16:00:00+09:00"))),
                clock
        );

        scheduler.runTick();
        clock.set("2026-04-30T12:01:03+09:00");
        scheduler.runTick();
        clock.set("2026-04-30T12:02:03+09:00");
        scheduler.runTick();

        assertThat(service.invocationCount.get()).isEqualTo(3);
    }

    @Test
    void atOrAfterScheduledStartTimeCallsSyncToday() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.SCHEDULED, "2026-04-30T18:30:00+09:00"))),
                clockAt("2026-04-30T18:30:00+09:00")
        );

        Duration nextDelay = scheduler.runTickAndGetNextDelay();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(nextDelay).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void liveGameStatusCallsSyncTodayAndBypassesHalfHourRule() {
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        LiveGameSyncScheduler scheduler = scheduler(
                service,
                properties(),
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
                properties(),
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

    @Test
    void dataAccessFailureSchedulesRetryAndNextRunSyncsNormally(CapturedOutput output) {
        LiveSyncProperties properties = properties();
        properties.setRetryInterval(Duration.ofSeconds(7));
        RecordingLiveGameSyncService service = new RecordingLiveGameSyncService();
        GameRepository repository = mock(GameRepository.class);
        when(repository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(any(LocalDate.class)))
                .thenThrow(new DataAccessResourceFailureException("connection closed"))
                .thenReturn(List.of(game(GameStatus.LIVE, "2026-04-30T18:30:00+09:00")));
        ScheduledHarness harness = scheduledHarness(service, properties, repository);

        harness.scheduler.start();
        Instant failureStartedAt = Instant.now();
        harness.tasks.get(0).run();
        Instant failureFinishedAt = Instant.now();

        assertThat(harness.tasks).hasSize(2);
        assertThat(harness.executionTimes.get(1)).isBetween(
                failureStartedAt.plus(Duration.ofSeconds(7)),
                failureFinishedAt.plus(Duration.ofSeconds(7))
        );
        assertThat(output).contains(
                "job=live-game-sync",
                "executionDate=2026-04-30",
                "exceptionType=org.springframework.dao.DataAccessResourceFailureException",
                "retryDelay=PT7S",
                "nextExecutionScheduled=true"
        );

        harness.tasks.get(1).run();

        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(harness.tasks).hasSize(3);
        verify(repository, times(2)).findByGameDateOrderByScheduledAtAscPublicGameIdAsc(GAME_DATE);
    }

    @Test
    void unexpectedRuntimeFailureReleasesRunningFlagAndNextRunSyncsNormally(CapturedOutput output) {
        FailingOnceLiveGameSyncService service = new FailingOnceLiveGameSyncService();
        ScheduledHarness harness = scheduledHarness(
                service,
                properties(),
                gameRepository(List.of(game(GameStatus.LIVE, "2026-04-30T18:30:00+09:00")))
        );

        harness.scheduler.start();
        Instant failureStartedAt = Instant.now();
        harness.tasks.get(0).run();
        Instant failureFinishedAt = Instant.now();

        assertThat(harness.tasks).hasSize(2);
        assertThat(harness.executionTimes.get(1)).isBetween(
                failureStartedAt.plus(Duration.ofSeconds(10)),
                failureFinishedAt.plus(Duration.ofSeconds(10))
        );
        assertThat(output).contains(
                "exceptionType=java.lang.IllegalStateException",
                "retryDelay=PT10S",
                "nextExecutionScheduled=true"
        );

        harness.tasks.get(1).run();

        assertThat(service.attemptCount.get()).isEqualTo(2);
        assertThat(service.invocationCount.get()).isEqualTo(1);
        assertThat(harness.tasks).hasSize(3);
    }

    private static LiveGameSyncScheduler scheduler(
            LiveGameSyncService service,
            LiveSyncProperties properties,
            GameRepository repository,
            Clock clock
    ) {
        return scheduler(service, properties, repository, clock, true);
    }

    private static LiveGameSyncScheduler scheduler(
            LiveGameSyncService service,
            LiveSyncProperties properties,
            GameRepository repository,
            Clock clock,
            boolean enabled
    ) {
        SyncProperties syncProperties = new SyncProperties();
        syncProperties.setEnabled(enabled);
        return new LiveGameSyncScheduler(service, syncProperties, properties, repository, clock);
    }

    private static ScheduledHarness scheduledHarness(
            LiveGameSyncService service,
            LiveSyncProperties properties,
            GameRepository repository
    ) {
        List<Runnable> tasks = new ArrayList<>();
        List<Instant> executionTimes = new ArrayList<>();
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = (ScheduledFuture<?>) Proxy.newProxyInstance(
                ScheduledFuture.class.getClassLoader(),
                new Class<?>[]{ScheduledFuture.class},
                (proxy, method, arguments) -> null
        );
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class))).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            executionTimes.add(invocation.getArgument(1));
            return future;
        });
        SyncProperties syncProperties = new SyncProperties();
        syncProperties.setEnabled(true);
        LiveGameSyncScheduler scheduler = new LiveGameSyncScheduler(
                service,
                syncProperties,
                properties,
                repository,
                clockAt("2026-04-30T12:01:00+09:00"),
                taskScheduler
        );
        return new ScheduledHarness(scheduler, tasks, executionTimes);
    }

    private static LiveSyncProperties properties() {
        return new LiveSyncProperties();
    }

    private static Clock clockAt(String offsetDateTime) {
        return Clock.fixed(OffsetDateTime.parse(offsetDateTime).toInstant(), KST);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(String offsetDateTime) {
            set(offsetDateTime);
        }

        private void set(String offsetDateTime) {
            this.instant = OffsetDateTime.parse(offsetDateTime).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return KST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
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

    private static Game finalConfirmedGame(String scheduledAt) {
        Game game = game(GameStatus.FINAL, scheduledAt);
        game.confirmFinal(OffsetDateTime.parse("2026-04-30T20:00:00+09:00"));
        return game;
    }

    private static class RecordingLiveGameSyncService extends LiveGameSyncService {

        protected final AtomicInteger invocationCount = new AtomicInteger();

        private RecordingLiveGameSyncService() {
            super(null, null, null, null, null, null, null, null);
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

    private static final class FailingOnceLiveGameSyncService extends RecordingLiveGameSyncService {

        private final AtomicInteger attemptCount = new AtomicInteger();

        @Override
        public LiveSyncSummary syncToday() {
            if (attemptCount.incrementAndGet() == 1) {
                throw new IllegalStateException("unexpected failure");
            }
            return super.syncToday();
        }
    }

    private record ScheduledHarness(
            LiveGameSyncScheduler scheduler,
            List<Runnable> tasks,
            List<Instant> executionTimes
    ) {
    }
}
