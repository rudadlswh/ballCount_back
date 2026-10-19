package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.api.InternalDetailRefreshOrchestrationController;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@ExtendWith(MockitoExtension.class)
class DetailRefreshOrchestratorServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-09T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Mock
    private LineScoreRepository lineScoreRepository;

    @Mock
    private GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository;

    private DetailRefreshOrchestratorService orchestratorService;

    @BeforeEach
    void setUp() {
        orchestratorService = new DetailRefreshOrchestratorService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                gameBoxscoreRecordReadRepository,
                new StubGameDetailImportService(),
                FIXED_CLOCK
        );
    }

    @Test
    void selectsScheduledGameForPregameRefreshAndStopsFinalCompleteGame() {
        Game scheduledGame = fixtureGame(
                "20260409-DOO-KIW",
                "20260409WOOB0",
                LocalDate.of(2026, 4, 9),
                GameStatus.SCHEDULED,
                null,
                null
        );
        Game finalGame = fixtureGame(
                "20260409-LG-KIA",
                "20260409HTLG0",
                LocalDate.of(2026, 4, 9),
                GameStatus.FINAL,
                7,
                2
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 9))))
                .thenReturn(List.of(scheduledGame, finalGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(scheduledGame.getId())))
                .thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(finalGame.getId())))
                .thenReturn(Optional.of(new GameSnapshot(
                        UUID.randomUUID(),
                        finalGame,
                        9,
                        "top",
                        "Top 9",
                        0,
                        0,
                        3,
                        false,
                        false,
                        false,
                        null,
                        null,
                        7,
                        2,
                        8,
                        7,
                        0,
                        1,
                        10,
                        3,
                        "hash",
                        null,
                        OffsetDateTime.of(2026, 4, 9, 20, 0, 0, 0, ZoneOffset.ofHours(9))
                )));
        when(lineScoreRepository.countByGame_Id(eq(scheduledGame.getId()))).thenReturn(0L);
        when(lineScoreRepository.countByGame_Id(eq(finalGame.getId()))).thenReturn(9L);
        when(gameBoxscoreRecordReadRepository.countBatterRecords(eq(finalGame.getId()))).thenReturn(18L);
        when(gameBoxscoreRecordReadRepository.countPitcherRecords(eq(finalGame.getId()))).thenReturn(8L);

        var result = orchestratorService.runPass(LocalDate.of(2026, 4, 9), false);

        assertThat(result.totalGames()).isEqualTo(2);
        assertThat(result.selectedGameCount()).isEqualTo(1);
        assertThat(result.decisions()).hasSize(2);
        assertThat(result.decisions().get(0).gameId()).isEqualTo("20260409-DOO-KIW");
        assertThat(result.decisions().get(0).selected()).isTrue();
        assertThat(result.decisions().get(0).phase()).isEqualTo("pregame");
        assertThat(result.decisions().get(0).refreshIntervalSeconds()).isEqualTo(1800);
        assertThat(result.decisions().get(1).gameId()).isEqualTo("20260409-LG-KIA");
        assertThat(result.decisions().get(1).selected()).isFalse();
        assertThat(result.decisions().get(1).phase()).isEqualTo("stopped");
        assertThat(result.decisions().get(1).finalDataComplete()).isTrue();
    }

    @Test
    void returnsEmptyDecisionSetWhenNoGamesExist() {
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 10))))
                .thenReturn(List.of());

        var result = orchestratorService.runPass(LocalDate.of(2026, 4, 10), false);

        assertThat(result.totalGames()).isZero();
        assertThat(result.selectedGameCount()).isZero();
        assertThat(result.decisions()).isEmpty();
        assertThat(result.executionResults()).isEmpty();
    }

    @Test
    void keepsFinalGameEligibleWhenBoxscoreRecordsAreMissing() {
        Game finalGame = fixtureGame(
                "20260409-LG-KIA",
                "20260409HTLG0",
                LocalDate.of(2026, 4, 9),
                GameStatus.FINAL,
                7,
                2
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 9))))
                .thenReturn(List.of(finalGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(finalGame.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.countByGame_Id(eq(finalGame.getId()))).thenReturn(9L);
        when(gameBoxscoreRecordReadRepository.countBatterRecords(eq(finalGame.getId()))).thenReturn(0L);
        when(gameBoxscoreRecordReadRepository.countPitcherRecords(eq(finalGame.getId()))).thenReturn(0L);

        var result = orchestratorService.runPass(LocalDate.of(2026, 4, 9), false);

        assertThat(result.selectedGameCount()).isEqualTo(1);
        assertThat(result.decisions().get(0).selected()).isTrue();
        assertThat(result.decisions().get(0).phase()).isEqualTo("post-final");
        assertThat(result.decisions().get(0).finalDataComplete()).isFalse();
    }

    @Test
    void skipsSameDateAndPhaseWhenSchedulerAndInternalExecuteConcurrently() throws Exception {
        LocalDate gameDate = LocalDate.of(2026, 4, 9);
        Game scheduledGame = fixtureGame(
                "20260409-DOO-KIW",
                "20260409WOOB0",
                gameDate,
                GameStatus.SCHEDULED,
                null,
                null
        );
        CountDownLatch importStarted = new CountDownLatch(1);
        CountDownLatch releaseImport = new CountDownLatch(1);
        RecordingGameDetailImportService importService = new RecordingGameDetailImportService(importStarted, releaseImport);
        DateSyncLockService lockService = new DateSyncLockService();
        DetailRefreshOrchestratorService schedulerOrchestrator = detailRefreshOrchestrator(importService, lockService);
        DetailRefreshOrchestratorService internalOrchestrator = detailRefreshOrchestrator(importService, lockService);
        InternalDetailRefreshOrchestrationController internalController =
                new InternalDetailRefreshOrchestrationController(internalOrchestrator);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(gameDate)))
                .thenReturn(List.of(scheduledGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(scheduledGame.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.countByGame_Id(eq(scheduledGame.getId()))).thenReturn(0L);

        FutureTask<DetailRefreshOrchestratorService.DetailRefreshPassResult> schedulerTask = new FutureTask<>(
                () -> schedulerOrchestrator.runPass(gameDate, true, Set.of(DetailRefreshOrchestratorService.RefreshPhase.PREGAME))
        );
        Thread schedulerThread = new Thread(schedulerTask, "detail-refresh-scheduler-test");
        schedulerThread.start();

        assertThat(importStarted.await(1, TimeUnit.SECONDS)).isTrue();
        var internalResult = internalController.runDetailRefreshPass(gameDate, true);
        releaseImport.countDown();
        var schedulerResult = schedulerTask.get(1, TimeUnit.SECONDS);

        assertThat(schedulerResult.executionResults()).hasSize(1);
        assertThat(internalResult.selectedGameCount()).isZero();
        assertThat(internalResult.executionResults()).isEmpty();
        assertThat(importService.importCount()).isEqualTo(1);
    }

    @Test
    void doesNotBlockDifferentDateOrDifferentPhase() {
        LocalDate firstDate = LocalDate.of(2026, 4, 9);
        LocalDate secondDate = LocalDate.of(2026, 4, 10);
        Game liveGame = fixtureGame(
                "20260409-LG-KIA",
                "20260409HTLG0",
                firstDate,
                GameStatus.LIVE,
                2,
                1
        );
        Game nextDateScheduledGame = fixtureGame(
                "20260410-DOO-KIW",
                "20260410WOOB0",
                secondDate,
                GameStatus.SCHEDULED,
                null,
                null
        );
        RecordingGameDetailImportService importService = new RecordingGameDetailImportService();
        DateSyncLockService lockService = new DateSyncLockService();
        DetailRefreshOrchestratorService service = detailRefreshOrchestrator(importService, lockService);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(firstDate)))
                .thenReturn(List.of(liveGame));
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(secondDate)))
                .thenReturn(List.of(nextDateScheduledGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(liveGame.getId())))
                .thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(nextDateScheduledGame.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.countByGame_Id(eq(liveGame.getId()))).thenReturn(0L);
        when(lineScoreRepository.countByGame_Id(eq(nextDateScheduledGame.getId()))).thenReturn(0L);

        try (DateSyncLockService.SyncLock ignored = lockService.tryDetailRefreshLock(firstDate, "pregame")) {
            var differentPhaseResult = service.runPass(
                    firstDate,
                    true,
                    Set.of(DetailRefreshOrchestratorService.RefreshPhase.LIVE)
            );
            var differentDateResult = service.runPass(
                    secondDate,
                    true,
                    Set.of(DetailRefreshOrchestratorService.RefreshPhase.PREGAME)
            );

            assertThat(differentPhaseResult.executionResults()).hasSize(1);
            assertThat(differentDateResult.executionResults()).hasSize(1);
            assertThat(importService.importedGameIds())
                    .containsExactlyInAnyOrder("20260409-LG-KIA", "20260410-DOO-KIW");
        }
    }

    private DetailRefreshOrchestratorService detailRefreshOrchestrator(
            GameDetailImportService importService,
            DateSyncLockService lockService
    ) {
        return new DetailRefreshOrchestratorService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                gameBoxscoreRecordReadRepository,
                importService,
                FIXED_CLOCK,
                lockService
        );
    }

    private Game fixtureGame(
            String publicGameId,
            String providerGameId,
            LocalDate gameDate,
            GameStatus status,
            Integer homeScore,
            Integer awayScore
    ) {
        Team homeTeam = new Team(UUID.randomUUID(), "lg", "LG Twins", "LG", "LG Twins", null);
        Team awayTeam = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        return new Game(
                UUID.randomUUID(),
                publicGameId,
                "kbo",
                providerGameId,
                gameDate,
                OffsetDateTime.of(gameDate.getYear(), gameDate.getMonthValue(), gameDate.getDayOfMonth(), 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                status,
                homeTeam,
                awayTeam,
                homeScore,
                awayScore,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private static final class StubGameDetailImportService extends GameDetailImportService {

        private StubGameDetailImportService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }
    }

    private static final class RecordingGameDetailImportService extends GameDetailImportService {

        private final CountDownLatch importStarted;
        private final CountDownLatch releaseImport;
        private final AtomicInteger importCount = new AtomicInteger();
        private final List<String> importedGameIds = new java.util.concurrent.CopyOnWriteArrayList<>();

        private RecordingGameDetailImportService() {
            this(null, null);
        }

        private RecordingGameDetailImportService(CountDownLatch importStarted, CountDownLatch releaseImport) {
            super(null, null, null, null, null, null, null, null, null, null);
            this.importStarted = importStarted;
            this.releaseImport = releaseImport;
        }

        @Override
        public GameDetailImportResult importGameDetail(String publicGameId) {
            importedGameIds.add(publicGameId);
            importCount.incrementAndGet();
            if (importStarted != null) {
                importStarted.countDown();
            }
            if (releaseImport != null) {
                try {
                    releaseImport.await(1, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for test import release", exception);
                }
            }
            return new GameDetailImportResult(
                    publicGameId,
                    publicGameId,
                    LocalDate.of(2026, 4, 9),
                    "live",
                    false,
                    false,
                    0,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    OffsetDateTime.now(FIXED_CLOCK)
            );
        }

        private int importCount() {
            return importCount.get();
        }

        private List<String> importedGameIds() {
            return importedGameIds;
        }
    }
}
