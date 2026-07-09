package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameEventReadRepository;
import com.kbo.crawlerapi.repository.GameEventReadRepository.GameEventRow;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.service.NotificationEventService.EventDeliveryResult;
import com.kbo.crawlerapi.service.NotificationEventService.NotificationEventDraft;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class LiveGameSyncServiceTest {

    private static final Clock ACTIVE_KST_CLOCK = Clock.fixed(
            Instant.parse("2026-04-09T09:31:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Test
    void todayUsesAsiaSeoulDate() {
        LiveGameSyncService service = service(
                Clock.fixed(Instant.parse("2026-04-27T15:30:00Z"), ZoneId.of("UTC")),
                new StubGameDetailImportService(),
                new StubNotificationEventService()
        );

        assertThat(service.todayKst()).isEqualTo(LocalDate.of(2026, 4, 28));
    }

    @Test
    void sameDateSyncSkipsConcurrentSecondRun() throws Exception {
        LocalDate date = LocalDate.of(2026, 4, 9);
        CountDownLatch firstRunEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRun = new CountDownLatch(1);
        AtomicInteger repositoryCalls = new AtomicInteger();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(date)))
                .thenAnswer(invocation -> {
                    if (repositoryCalls.incrementAndGet() == 1) {
                        firstRunEntered.countDown();
                        releaseFirstRun.await(1, TimeUnit.SECONDS);
                    }
                    return List.of();
                });
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), new StubNotificationEventService());
        var executor = Executors.newSingleThreadExecutor();

        try {
            var first = executor.submit(() -> service.sync(date, false));
            assertThat(firstRunEntered.await(1, TimeUnit.SECONDS)).isTrue();

            LiveGameSyncService.LiveSyncSummary second = service.sync(date, false);

            assertThat(second.scannedCount()).isZero();
            assertThat(second.notificationSkippedCount()).isEqualTo(1);
            assertThat(second.errors()).containsExactly("sync already in progress");

            releaseFirstRun.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS).date()).isEqualTo(date);
        } finally {
            releaseFirstRun.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void gameFourHoursAndOneMinuteBeforeStartIsNotEligibleOutsideActiveWindow() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-08T20:59:00Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 10, 0, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
    }

    @Test
    void scheduledPregameGameExactlyFourHoursBeforeStartSkipsDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-08T21:00:00Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 10, 0, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
    }

    @Test
    void scheduledPregameGameThreeHoursAndFiftyNineMinutesBeforeStartSkipsDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-08T21:01:00Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 10, 0, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
    }

    @Test
    void scheduledGameOneMinuteBeforeStartSkipsDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T09:29:00Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
    }

    @Test
    void scheduledGameExactlyAtScheduledAtRunsDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T09:30:00Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(game);
        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).containsExactly(game.getPublicGameId());
    }

    @Test
    void candidateDiagnosticsIncludeProviderGameId(CapturedOutput output) {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T09:30:00Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(game);
        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        service.sync(game.getGameDate(), false);

        assertThat(output.getOut()).contains("candidate diagnostics");
        assertThat(output.getOut()).contains("providerGameId=" + game.getProviderGameId());
    }

    @Test
    void scheduledGameAfterScheduledAtRunsDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T09:35:57Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(game);
        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).containsExactly(game.getPublicGameId());
    }

    @Test
    void scheduledStartCandidateUsesLiveTtlAfterScheduledAt() {
        MutableClock clock = new MutableClock("2026-04-09T09:35:57Z");
        Game game = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(game);
        LiveSyncProperties properties = new LiveSyncProperties();
        properties.setLiveTtl(Duration.ofSeconds(3));
        properties.setPregameTtl(Duration.ofMinutes(3));
        LiveGameSyncService service = service(
                clock,
                importService,
                new StubNotificationEventService(),
                null,
                properties
        );

        service.sync(game.getGameDate(), false);
        service.sync(game.getGameDate(), false);
        clock.advance(Duration.ofSeconds(3));
        service.sync(game.getGameDate(), false);

        assertThat(importService.importedGameIds)
                .containsExactly(game.getPublicGameId(), game.getPublicGameId());
    }

    @Test
    void unknownGameAfterScheduledAtRunsDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T09:35:57Z"), ZoneId.of("Asia/Seoul"));
        Game game = fixtureGame(
                GameStatus.UNKNOWN,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(game);
        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).containsExactly(game.getPublicGameId());
    }

    @Test
    void cancelledAndPostponedGamesAfterScheduledAtSkipDetailImport() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T09:35:57Z"), ZoneId.of("Asia/Seoul"));
        Game cancelled = fixtureGame(
                GameStatus.CANCELLED,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                true,
                false,
                GameCancelReason.RAIN,
                "우천취소"
        );
        Game postponed = fixtureGame(
                GameStatus.POSTPONED,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                false,
                true,
                null,
                "순연"
        );
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(cancelled.getGameDate())))
                .thenReturn(List.of(cancelled, postponed));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(cancelled.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(postponed.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(cancelled.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
    }

    @Test
    void liveAndSuspendedGamesRemainDetailImportCandidates() {
        Clock clock = Clock.fixed(Instant.parse("2026-04-09T03:01:00Z"), ZoneId.of("Asia/Seoul"));
        Game live = fixtureGameWithPublicGameId("20260409-LG-KIA-LIVE", GameStatus.LIVE);
        Game suspended = fixtureGameWithPublicGameId("20260409-LG-KIA-SUSPENDED", GameStatus.SUSPENDED);
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(live.getGameDate())))
                .thenReturn(List.of(live, suspended));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(live.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(suspended.getId()))).thenReturn(Optional.empty());
        when(gameRepository.findByPublicGameId(eq(live.getPublicGameId()))).thenReturn(Optional.of(live));
        when(gameRepository.findByPublicGameId(eq(suspended.getPublicGameId()))).thenReturn(Optional.of(suspended));
        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(live.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).containsExactly(live.getPublicGameId(), suspended.getPublicGameId());
    }

    @Test
    void liveScoreChangeCreatesScoringNotificationDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0);
        Game after = fixtureGame(GameStatus.LIVE, 2, 0);
        GameSnapshot snapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(snapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly("SCORE_CHANGED");
        assertThat(notificationEventService.drafts.get(0).title())
                .isEqualTo("KIA 득점");
        assertThat(notificationEventService.drafts.get(0).body())
                .isEqualTo("3회초 KIA 1득점 · KIA 2-0 LG");
        assertThat(notificationEventService.drafts.get(0).eventKey())
                .isEqualTo("score:%s:3:top:2:0".formatted(after.getId()))
                .doesNotContain("inning:", "batter:", "pitcher:", "result:");
    }

    @Test
    void liveScoreChangeNotificationUsesMergedLatestScoreFromGameRow() {
        Game before = fixtureGame(GameStatus.LIVE, 10, 9);
        Game after = fixtureGame(GameStatus.LIVE, 11, 9);
        GameSnapshot snapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(snapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_SCORE_CHANGED);
        assertThat(notificationEventService.drafts.get(0).eventKey())
                .isEqualTo("score:%s:3:top:11:9".formatted(after.getId()));
        assertThat(notificationEventService.drafts.get(0).body())
                .contains("KIA 11-9 LG");
    }

    @Test
    void liveScoreChangeUsesParsedScoringPlayDetailWithoutChangingDedupeKey() {
        Game before = fixtureGame(GameStatus.LIVE, 2, 2);
        Game after = fixtureGame(GameStatus.LIVE, 4, 2);
        GameSnapshot beforeSnapshot = snapshot(before, "레이예스", "홈투수", 0, true, true, false, 0, 0, 7, "top", "Top 7");
        GameSnapshot afterSnapshot = snapshot(after, "후속타자", "홈투수", 0, false, false, false, 0, 0, 7, "top", "Top 7");
        StubGameEventReadRepository gameEventReadRepository = new StubGameEventReadRepository(List.of(
                new GameEventRow(20, 7, "top", "HIT", "레이예스 : 좌익수 왼쪽 2루타"),
                new GameEventRow(21, 7, "top", "RUN_SCORED", "2루주자 최항 : 홈인"),
                new GameEventRow(22, 7, "top", "RUN_SCORED", "1루주자 손성빈 : 홈인")
        ));

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(),
                notificationEventService,
                gameEventReadRepository
        );

        service.sync(before.getGameDate(), false);

        NotificationEventDraft draft = notificationEventService.drafts.stream()
                .filter(candidate -> NotificationEventService.EVENT_SCORE_CHANGED.equals(candidate.eventType()))
                .findFirst()
                .orElseThrow();
        assertThat(draft.eventType()).isEqualTo(NotificationEventService.EVENT_SCORE_CHANGED);
        assertThat(draft.eventKey()).isEqualTo("score:%s:7:top:4:2".formatted(after.getId()));
        assertThat(draft.title()).isEqualTo("KIA 득점");
        assertThat(draft.body()).isEqualTo("7회초 레이예스 좌익수 왼쪽 2루타, 2득점 · KIA 4-2 LG");
        assertThat(draft.payload())
                .containsEntry("scoringBatterName", "레이예스")
                .containsEntry("scoringResultText", "2루타")
                .containsEntry("scoringSelectedEventType", "HIT")
                .containsEntry("scoringSelectedEventText", "레이예스 : 좌익수 왼쪽 2루타")
                .containsEntry("scoringRunScoredEventCount", 2)
                .containsEntry("scoringRunsScored", 2)
                .containsEntry(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, "kia");
    }

    @Test
    void staleParsedScoringPlayBatterIsRejectedForPreviousBatterFallback() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                "레이예스",
                "나승엽",
                List.of(new GameEventRow(30, 3, "top", "OUT", "황성빈 : 2루수 땅볼"))
        );

        assertThat(draft.title()).isEqualTo("롯데 득점");
        assertThat(draft.body()).isEqualTo("3회초 레이예스 득점 상황, 2득점 · 롯데 2-5 LG");
        assertThat(draft.body()).doesNotContain("황성빈", "땅볼");
        assertThat(draft.payload()).doesNotContainKey("scoringBatterName");
    }

    @Test
    void homeRunTextCreatesScoringDetailWithoutBatterRecordIncrease() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                "레이예스",
                "나승엽",
                List.of(new GameEventRow(30, 3, "top", "HOME_RUN", "레이예스 : 좌월 홈런"))
        );

        assertThat(draft.title()).isEqualTo("롯데 득점");
        assertThat(draft.body()).isEqualTo("3회초 레이예스 좌월 홈런, 2득점 · 롯데 2-5 LG");
        assertThat(draft.payload())
                .containsEntry("scoringBatterName", "레이예스")
                .containsEntry("scoringResultText", "홈런");
    }

    @Test
    void runScoredBeforeHitSequenceStillSelectsHitCauseEventText() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                "레이예스",
                "나승엽",
                List.of(
                        new GameEventRow(124, 3, "top", "RUN_SCORED", "3루주자 신윤후 : 홈인"),
                        new GameEventRow(125, 3, "top", "RUN_SCORED", "1루주자 고승민 : 홈인"),
                        new GameEventRow(126, 3, "top", "HIT", "레이예스 : 우중간 2루타")
                ),
                true,
                false,
                true
        );

        assertThat(draft.title()).isEqualTo("롯데 득점");
        assertThat(draft.body()).isEqualTo("3회초 레이예스 우중간 2루타, 2득점 · 롯데 2-5 LG");
        assertThat(draft.payload())
                .containsEntry("scoringBatterName", "레이예스")
                .containsEntry("scoringResultText", "2루타")
                .containsEntry("scoringSelectedEventType", "HIT")
                .containsEntry("scoringSelectedEventText", "레이예스 : 우중간 2루타")
                .containsEntry("scoringRunsScored", 2);
    }

    @Test
    void homeRunWithFourRunDeltaUsesHomeRunEventTextInsteadOfScoreOnlyFallback() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                4,
                5,
                "전민재",
                "후속타자",
                List.of(new GameEventRow(130, 3, "top", "HOME_RUN", "전민재 : 좌익수 뒤 홈런 (홈런거리:110M)")),
                true,
                true,
                true
        );

        assertThat(draft.title()).isEqualTo("롯데 득점");
        assertThat(draft.body()).isEqualTo("3회초 전민재 좌익수 뒤 홈런 (홈런거리:110M), 4득점 · 롯데 4-5 LG");
        assertThat(draft.body()).doesNotStartWith("4득점");
        assertThat(draft.payload())
                .containsEntry("scoringResultText", "홈런")
                .containsEntry("scoringSelectedEventType", "HOME_RUN")
                .containsEntry("scoringSelectedEventText", "전민재 : 좌익수 뒤 홈런 (홈런거리:110M)");
    }

    @Test
    void runScoredEventsOnlyUseFallbackAndNeverUseRunnerTextAsMainMessage() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                "레이예스",
                "나승엽",
                List.of(
                        new GameEventRow(124, 3, "top", "RUN_SCORED", "3루주자 신윤후 : 홈인"),
                        new GameEventRow(125, 3, "top", "RUN_SCORED", "1루주자 고승민 : 홈인")
                )
        );

        assertThat(draft.title()).isEqualTo("롯데 득점");
        assertThat(draft.body()).isEqualTo("3회초 레이예스 득점 상황, 2득점 · 롯데 2-5 LG");
        assertThat(draft.body()).doesNotContain("신윤후", "고승민", "홈인");
        assertThat(draft.payload()).doesNotContainKey("scoringSelectedEventText");
    }

    @Test
    void parsedScoringPlayWithRunCountMismatchIsRejected() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                "레이예스",
                "나승엽",
                List.of(new GameEventRow(30, 3, "top", "HOME_RUN", "레이예스 : 좌월 홈런, 1득점"))
        );

        assertThat(draft.body()).isEqualTo("3회초 레이예스 득점 상황, 2득점 · 롯데 2-5 LG");
        assertThat(draft.payload()).doesNotContainKey("scoringBatterName");
    }

    @Test
    void parsedScoringPlayWithScoreAfterMismatchIsRejected() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                "레이예스",
                "나승엽",
                List.of(new GameEventRow(30, 3, "top", "HOME_RUN", "레이예스 : 좌월 홈런 · 롯데 1-5 LG"))
        );

        assertThat(draft.body()).isEqualTo("3회초 레이예스 득점 상황, 2득점 · 롯데 2-5 LG");
        assertThat(draft.payload()).doesNotContainKey("scoringBatterName");
    }

    @Test
    void missingPreviousBatterRejectsBatterSpecificUnreliableEventForGenericFallback() {
        NotificationEventDraft draft = scoreChangeDraftForLotteLg(
                0,
                5,
                2,
                5,
                null,
                "나승엽",
                List.of(new GameEventRow(30, 3, "top", "OUT", "황성빈 : 2루수 땅볼"))
        );

        assertThat(draft.title()).isEqualTo("롯데 득점");
        assertThat(draft.body()).isEqualTo("3회초 롯데 2득점 · 롯데 2-5 LG");
        assertThat(draft.body()).doesNotContain("황성빈", "땅볼");
    }

    @Test
    void scheduledToLiveTransitionCreatesGameStartDraftOnce() {
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0);
        Game afterSchedule = fixtureGame(GameStatus.LIVE, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 0, 0);
        GameSnapshot snapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(afterSchedule.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(snapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_START);
        assertThat(notificationEventService.drafts.get(0).eventKey()).isEqualTo("game:%s:game-start".formatted(after.getId()));
    }

    @Test
    void scheduleLiveTransitionCreatesGameStartEvenWhenDetailImportFails(CapturedOutput output) {
        Clock delayedClock = Clock.fixed(Instant.parse("2026-04-09T09:55:00Z"), ZoneId.of("Asia/Seoul"));
        OffsetDateTime scheduledAt = OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9));
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0, null, scheduledAt);
        Game afterSchedule = fixtureGame(GameStatus.LIVE, 1, 0, "Top 1", scheduledAt);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(afterSchedule.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                delayedClock,
                new StubGameDetailImportService(new IllegalStateException("detail parse failed")),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_START);
        assertThat(output.getOut()).contains("delayed GAME_START detected");
        assertThat(output.getOut()).contains("delayMinutes=25");
    }

    @Test
    void liveTransitionBeforeScheduledAtDoesNotCreateGameStart() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-25T08:52:48Z"), ZoneId.of("Asia/Seoul"));
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-06-25T09:30:00Z");
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0, null, scheduledAt);
        Game afterSchedule = fixtureGame(GameStatus.LIVE, 1, 0, "Top 1", scheduledAt);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(afterSchedule.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(clock, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
        assertThat(afterSchedule.getStatus()).isEqualTo(GameStatus.SCHEDULED);
    }

    @Test
    void stalePregameSnapshotDoesNotPromoteScheduledGameToLiveOrGameStart() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-25T09:52:24Z"), ZoneId.of("Asia/Seoul"));
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-06-25T09:30:00Z");
        OffsetDateTime staleSnapshotAt = OffsetDateTime.parse("2026-06-25T08:53:13Z");
        Game before = fixtureGameWithTeamsAndSchedule(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                scheduledAt,
                "nc",
                "NC 다이노스",
                "NC",
                "lotte",
                "롯데 자이언츠",
                "롯데"
        );
        Game after = fixtureGameWithTeamsAndSchedule(
                GameStatus.LIVE,
                0,
                0,
                "Top 1",
                scheduledAt,
                "nc",
                "NC 다이노스",
                "NC",
                "lotte",
                "롯데 자이언츠",
                "롯데"
        );
        GameSnapshot stalePlaceholder = snapshotAt(after, null, null, 0, false, false, false, 0, 0, 1, "top", "Top 1", staleSnapshotAt);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(after));
        when(gameRepository.findByPublicGameId(eq(after.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(stalePlaceholder));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(clock, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
        assertThat(after.getStatus()).isEqualTo(GameStatus.SCHEDULED);
    }

    @Test
    void firstInningScorelessZeroCountPlaceholderDoesNotCreateGameStart() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-25T09:52:24Z"), ZoneId.of("Asia/Seoul"));
        OffsetDateTime scheduledAt = OffsetDateTime.parse("2026-06-25T09:30:00Z");
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0, null, scheduledAt);
        Game after = fixtureGame(GameStatus.LIVE, 0, 0, "Top 1", scheduledAt);
        GameSnapshot placeholder = snapshotAt(after, "선두타자", "선발투수", 0, false, false, false, 0, 0, 1, "top", "Top 1", OffsetDateTime.now(clock));

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(after));
        when(gameRepository.findByPublicGameId(eq(after.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(placeholder));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(clock, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
        assertThat(after.getStatus()).isEqualTo(GameStatus.SCHEDULED);
    }

    @Test
    void repeatedLiveStateDoesNotCreateGameStartDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 0, 0);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void liveToFinalTransitionCreatesGameEndDraftOnce() {
        Game before = fixtureGame(GameStatus.LIVE, 3, 2);
        Game after = withStatusReason(fixtureGame(GameStatus.FINAL, 4, 2), "GAME_RESULT_CK=1");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_END);
        assertThat(notificationEventService.drafts.get(0).payload())
                .containsEntry("winningTeamId", "kia")
                .containsEntry("losingTeamId", "lg");
        assertThat(after.getFinalConfirmedAt()).isNotNull();
    }

    @Test
    void liveLikeNinthInningWithoutOfficialFinalMarkerDoesNotCreateGameEndDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 4, 6, "Bottom 9");
        Game after = fixtureGame(GameStatus.LIVE, 4, 6, "Bottom 9");
        GameSnapshot beforeSnapshot = snapshot(before, "황영묵", "최준용", 1, false, false, false, 0, 0, 9, "bottom", "Bottom 9");
        GameSnapshot afterSnapshot = snapshot(after, "황영묵", "최준용", 1, false, false, false, 0, 0, 9, "bottom", "Bottom 9");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
        assertThat(after.getFinalConfirmedAt()).isNull();
    }

    @Test
    void liveToRainInterruptedTransitionCreatesGameInterruptedDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 2, "Top 8");
        Game after = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천중단");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts).hasSize(1);
        NotificationEventDraft draft = notificationEventService.drafts.get(0);
        assertThat(draft.eventType()).isEqualTo(NotificationEventService.EVENT_GAME_SUSPENDED);
        assertThat(draft.eventKey()).isEqualTo("game:%s:suspended".formatted(after.getId()));
        assertThat(draft.title()).isEqualTo("경기 중단");
        assertThat(draft.body()).isEqualTo("KIA vs LG 경기가 우천으로 일시 중단되었습니다.");
        assertThat(draft.payload())
                .containsEntry("status", "suspended")
                .containsEntry("statusReason", "우천중단");
    }

    @Test
    void weakFinalToRainInterruptedTransitionCreatesGameInterruptedDraft() {
        Game before = fixtureGame(GameStatus.FINAL, 1, 2, "Top 8");
        before.confirmFinal(OffsetDateTime.now(ACTIVE_KST_CLOCK));
        Game after = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천중단");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_SUSPENDED);
    }

    @Test
    void repeatedRainInterruptedStateDoesNotCreateInterruptedDraft() {
        Game before = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천중단");
        Game after = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천중단");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void rainInterruptedToLiveTransitionCreatesGameResumedDraft() {
        Game before = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천중단");
        Game after = fixtureGame(GameStatus.LIVE, 1, 2, "Top 8");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_RESUMED);
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("KIA vs LG 경기가 재개되었습니다.");
    }

    @Test
    void scheduledRainDelayCreatesDelayedDraftWithoutGameStart() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-25T09:35:00Z"), ZoneId.of("Asia/Seoul"));
        OffsetDateTime scheduledAt = OffsetDateTime.of(2026, 6, 25, 18, 30, 0, 0, ZoneOffset.ofHours(9));
        Game before = fixtureGameWithTeamsAndSchedule(
                GameStatus.SCHEDULED,
                null,
                null,
                null,
                scheduledAt,
                "lotte",
                "롯데 자이언츠",
                "롯데",
                "doosan",
                "두산 베어스",
                "두산",
                "잠실"
        );
        Game afterSchedule = withStatusReason(fixtureGameWithTeamsAndSchedule(
                GameStatus.DELAYED,
                null,
                null,
                null,
                scheduledAt,
                "lotte",
                "롯데 자이언츠",
                "롯데",
                "doosan",
                "두산 베어스",
                "두산",
                "잠실"
        ), "우천 지연");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameRepository.findByPublicGameId(eq(afterSchedule.getPublicGameId()))).thenReturn(Optional.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(afterSchedule.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(clock, new StubGameDetailImportService(), new StubScheduleImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_DELAYED);
        NotificationEventDraft draft = notificationEventService.drafts.get(0);
        assertThat(draft.title()).isEqualTo("경기 지연");
        assertThat(draft.body()).isEqualTo("롯데 vs 두산 경기가 우천으로 지연되고 있습니다. (잠실, 18:30)");
        assertThat(draft.eventKey()).isEqualTo("game:%s:delayed".formatted(afterSchedule.getId()));
    }

    @Test
    void repeatedRainDelayedStateDoesNotCreateDelayedDraft() {
        Game before = withStatusReason(fixtureGame(GameStatus.DELAYED, 0, 0, null), "우천 지연");
        Game after = withStatusReason(fixtureGame(GameStatus.DELAYED, 0, 0, null), "우천 지연");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void scheduledTopFirstWithSingleSnapshotLogsSuspiciousStall(CapturedOutput output) {
        Game scheduled = fixtureGame(GameStatus.SCHEDULED, 0, 0, "Top 1");
        scheduled.markLiveChecked(OffsetDateTime.of(2026, 4, 9, 18, 42, 38, 0, ZoneOffset.ofHours(9)));
        GameSnapshot snapshot = snapshotAt(
                scheduled,
                null,
                null,
                0,
                false,
                false,
                false,
                0,
                0,
                1,
                "top",
                "Top 1",
                OffsetDateTime.of(2026, 4, 9, 18, 30, 12, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(scheduled.getGameDate())))
                .thenReturn(List.of(scheduled), List.of(scheduled));
        when(gameRepository.findByPublicGameId(eq(scheduled.getPublicGameId()))).thenReturn(Optional.of(scheduled));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(scheduled.getId()))).thenReturn(Optional.of(snapshot));
        when(gameSnapshotRepository.countByGame_Id(eq(scheduled.getId()))).thenReturn(1L);

        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), new StubScheduleImportService(), new StubNotificationEventService());

        service.sync(scheduled.getGameDate(), false);

        assertThat(output.getOut()).contains("suspicious scheduled stall");
        assertThat(output.getOut()).contains("scheduled_after_start");
        assertThat(output.getOut()).contains("inning_top_or_bottom_1_or_later");
        assertThat(output.getOut()).contains("snapshot_count_le_1");
        assertThat(output.getOut()).contains("live_last_checked_only");
    }

    @Test
    void rainDelayResumeScheduleCreatesSingleResumeScheduledDraft() {
        Game before = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천 지연");
        Game after = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "19:10 재개 예정");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_RESUME_SCHEDULED);
        NotificationEventDraft draft = notificationEventService.drafts.get(0);
        assertThat(draft.body()).isEqualTo("KIA vs LG 경기가 19:10 재개 예정입니다.");
        assertThat(draft.eventKey()).isEqualTo("game:%s:resume-scheduled:19:10".formatted(after.getId()));
    }

    @Test
    void repeatedResumeScheduledStateDoesNotCreateDuplicateDraft() {
        Game before = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "19:10 재개 예정");
        Game after = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "19:10 재개 예정");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void resumeScheduledWithoutExplicitTimeDoesNotCreateResumeScheduledDraft() {
        Game before = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "우천 지연");
        Game after = withStatusReason(fixtureGame(GameStatus.SUSPENDED, 1, 2, "Top 8"), "재개 시간 미정");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 2, 8, "top", "Top 8");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void scheduledToCancelledTransitionCreatesGameCancelledDraftOnce() {
        Game before = fixtureGame(GameStatus.SCHEDULED, null, null);
        Game after = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts).hasSize(1);
        NotificationEventDraft draft = notificationEventService.drafts.get(0);
        assertThat(draft.eventType()).isEqualTo(NotificationEventService.EVENT_GAME_CANCELLED);
        assertThat(draft.eventKey()).isEqualTo("game:%s:game-cancelled".formatted(after.getId()));
        assertThat(draft.title()).isEqualTo("경기 취소");
        assertThat(draft.body()).isEqualTo("KIA vs LG 경기가 우천취소되었습니다. (잠실, 18:30)");
        assertThat(draft.payload())
                .containsEntry("cancelReason", "rain")
                .containsEntry("rawCancelText", "우천취소");
    }

    @Test
    void repeatedCancelledStateDoesNotCreateGameCancelledDraft() {
        Game before = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void scheduledToPostponedTransitionCreatesGameCancelledDraftWithPostponedBody() {
        Game before = fixtureGame(GameStatus.SCHEDULED, null, null);
        Game after = cancelledFixtureGame(GameStatus.POSTPONED, null, "순연");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_CANCELLED);
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("KIA vs LG 경기가 순연되었습니다. (잠실, 18:30)");
    }

    @Test
    void cancelledScheduleStatusCreatesGameCancelledWithoutDetailImport() {
        Game before = fixtureGame(GameStatus.SCHEDULED, null, null);
        Game afterSchedule = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubGameDetailImportService importService = new StubGameDetailImportService();
        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                importService,
                new StubScheduleImportService(),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_CANCELLED);
        assertThat(notificationEventService.drafts.get(0).eventKey())
                .isEqualTo("game:%s:game-cancelled".formatted(afterSchedule.getId()));
    }

    @Test
    void detailParseFailureDoesNotBlockScheduleCancellationEventCreation() {
        Game before = fixtureGame(GameStatus.SCHEDULED, null, null);
        Game afterSchedule = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubGameDetailImportService importService = new StubGameDetailImportService(
                new IllegalStateException("Failed to parse KBO game detail response")
        );
        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                importService,
                new StubScheduleImportService(),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.failedCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_CANCELLED);
    }

    @Test
    void repeatedScheduleCancellationSyncDoesNotCreateDuplicateDraft() {
        Game before = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");
        Game afterSchedule = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(),
                new StubScheduleImportService(),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void nonCancelledDetailParseFailureDoesNotCreateEvent() {
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0);
        Game afterSchedule = fixtureGame(GameStatus.SCHEDULED, 0, 0);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before), List.of(afterSchedule));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(new IllegalStateException("Failed to parse KBO game detail response")),
                new StubScheduleImportService(),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void liveDetailParseFailureIsStillHandledAsFailure() {
        Game live = fixtureGame(GameStatus.LIVE, 0, 0);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(live.getGameDate())))
                .thenReturn(List.of(live));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(live.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        StubGameDetailImportService importService = new StubGameDetailImportService(
                new IllegalStateException("Failed to parse KBO game detail response")
        );
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                importService,
                new StubScheduleImportService(),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(live.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(importService.importedGameIds).containsExactly(live.getPublicGameId());
        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void repeatedPregameChecksForScheduledGamesDoNotCreateFailures() {
        Game scheduled = fixtureGame(
                GameStatus.SCHEDULED,
                0,
                0,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 35, 0, 0, ZoneOffset.ofHours(9))
        );
        StubGameDetailImportService importService = new StubGameDetailImportService(
                new IllegalStateException("Failed to parse KBO game detail response")
        );
        StubNotificationEventService notificationEventService = new StubNotificationEventService();

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(scheduled.getGameDate())))
                .thenReturn(List.of(scheduled));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(scheduled.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                importService,
                new StubScheduleImportService(),
                notificationEventService
        );

        LiveGameSyncService.LiveSyncSummary first = service.sync(scheduled.getGameDate(), false);
        LiveGameSyncService.LiveSyncSummary second = service.sync(scheduled.getGameDate(), false);

        assertThat(first.failedCount()).isZero();
        assertThat(second.failedCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void liveToFinalTransitionTriggersTeamRankRefresh() {
        Game before = fixtureGame(GameStatus.LIVE, 3, 2);
        Game after = withStatusReason(fixtureGame(GameStatus.FINAL, 4, 2), "GAME_RESULT_CK=1");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());

        StubTeamRankService teamRankService = new StubTeamRankService(false);
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(),
                new StubNotificationEventService(),
                teamRankService
        );

        service.sync(before.getGameDate(), false);

        assertThat(teamRankService.refreshedSeasons).containsExactly(2026);
    }

    @Test
    void teamRankRefreshFailureDoesNotBreakLiveSync() {
        Game before = fixtureGame(GameStatus.LIVE, 3, 2);
        Game after = withStatusReason(fixtureGame(GameStatus.FINAL, 4, 2), "GAME_RESULT_CK=1");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());

        StubTeamRankService teamRankService = new StubTeamRankService(true);
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(),
                new StubNotificationEventService(),
                teamRankService
        );

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.failedCount()).isZero();
        assertThat(teamRankService.refreshedSeasons).containsExactly(2026);
    }

    @Test
    void repeatedFinalStateDoesNotCreateGameEndDraft() {
        Game before = fixtureGame(GameStatus.FINAL, 4, 2);
        Game after = fixtureGame(GameStatus.FINAL, 4, 2);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void unconfirmedFinalRecoversToLiveOnNextLiveLikeCrawl() {
        Game before = fixtureGame(GameStatus.FINAL, 4, 6, "Bottom 9");
        Game after = fixtureGame(GameStatus.LIVE, 4, 6, "Bottom 9");
        GameSnapshot beforeSnapshot = snapshot(before, "황영묵", "최준용", 1, false, false, false, 0, 0, 9, "bottom", "Bottom 9");
        GameSnapshot afterSnapshot = snapshot(after, "황영묵", "최준용", 1, false, false, false, 0, 0, 9, "bottom", "Bottom 9");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(after.getStatus()).isEqualTo(GameStatus.LIVE);
        assertThat(after.getFinalConfirmedAt()).isNull();
        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void batterReachingBaseCreatesOnBaseDraftWithoutScoreChange() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0, "Top 3");
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Top 3");
        GameSnapshot beforeSnapshot = snapshot(before, "이전타자", "박투수", 1, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "홍길동", "박투수", 1, true, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly("ON_BASE");
        assertThat(notificationEventService.drafts.get(0).body())
                .isEqualTo("3회초 이전타자 출루 · KIA 1-0 LG");
    }

    @Test
    void onBaseDraftIncludesReachMethodWhenAvailable() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Top 3 볼넷");
        GameSnapshot beforeSnapshot = snapshot(before, "윤동희", "박투수", 1, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "다음타자", "박투수", 1, true, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("3회초 윤동희 볼넷 · KIA 1-0 LG");
    }

    @Test
    void onBaseUsesOfficialDoubleWhenPreviousBatterReachesSecond() {
        NotificationEventDraft draft = onBaseDraftForLotteLg(
                "전준우",
                null,
                "전준우",
                null,
                List.of(new GameEventRow(30, 4, "top", "HIT", "전준우 : 좌익수 왼쪽 2루타")),
                new LiveSyncProperties()
        );

        assertThat(draft.eventKey()).contains("onbase:").doesNotContain("detail:");
        assertThat(draft.title()).isEqualTo("롯데 출루");
        assertThat(draft.body()).isEqualTo("4회초 전준우 2루타 · 롯데 2-5 LG");
        assertThat(draft.payload())
                .containsEntry("onBaseBatterName", "전준우")
                .containsEntry("onBaseResultText", "2루타")
                .containsEntry("onBaseReachedBase", 2)
                .containsEntry("onBaseDetailSource", "officialText");
    }

    @Test
    void onBaseFallsBackToSecondBaseReachedWhenOfficialTextMissing() {
        NotificationEventDraft draft = onBaseDraftForLotteLg(
                "전준우",
                null,
                "전준우",
                null,
                List.of(),
                new LiveSyncProperties()
        );

        assertThat(draft.title()).isEqualTo("롯데 출루");
        assertThat(draft.body()).isEqualTo("4회초 전준우 2루 도달 · 롯데 2-5 LG");
        assertThat(draft.payload())
                .containsEntry("onBaseResultText", null)
                .containsEntry("onBaseReachedBase", 2)
                .containsEntry("onBaseDetailSource", "snapshotDiff");
    }

    @Test
    void onBaseUsesOfficialSingleWhenPreviousBatterReachesFirst() {
        NotificationEventDraft draft = onBaseDraftForLotteLg(
                "전준우",
                "전준우",
                null,
                null,
                List.of(new GameEventRow(30, 4, "top", "HIT", "전준우 : 중전 안타")),
                new LiveSyncProperties()
        );

        assertThat(draft.body()).isEqualTo("4회초 전준우 안타 · 롯데 2-5 LG");
        assertThat(draft.payload()).containsEntry("onBaseResultText", "안타");
    }

    @Test
    void onBaseUsesOfficialWalkWhenPreviousBatterReachesFirst() {
        NotificationEventDraft draft = onBaseDraftForLotteLg(
                "전준우",
                "전준우",
                null,
                null,
                List.of(new GameEventRow(30, 4, "top", "WALK", "전준우 : 볼넷")),
                new LiveSyncProperties()
        );

        assertThat(draft.body()).isEqualTo("4회초 전준우 볼넷 · 롯데 2-5 LG");
        assertThat(draft.payload()).containsEntry("onBaseResultText", "볼넷");
    }

    @Test
    void onBaseRejectsOfficialTextForDifferentBatter() {
        NotificationEventDraft draft = onBaseDraftForLotteLg(
                "전준우",
                null,
                "전준우",
                null,
                List.of(new GameEventRow(30, 4, "top", "HIT", "황성빈 : 좌익수 왼쪽 2루타")),
                new LiveSyncProperties()
        );

        assertThat(draft.body()).isEqualTo("4회초 전준우 2루 도달 · 롯데 2-5 LG");
        assertThat(draft.body()).doesNotContain("황성빈");
        assertThat(draft.payload()).containsEntry("onBaseDetailSource", "snapshotDiff");
    }

    @Test
    void onBaseDetailTimeoutSendsSnapshotDiffFallback() {
        LiveSyncProperties properties = new LiveSyncProperties();
        properties.setDetailExtractionTimeout(Duration.ofMillis(25));
        NotificationEventDraft draft = onBaseDraftForLotteLg(
                "전준우",
                null,
                "전준우",
                null,
                new SlowGameEventReadRepository(
                        List.of(new GameEventRow(30, 4, "top", "HIT", "전준우 : 좌익수 왼쪽 2루타")),
                        200
                ),
                properties
        );

        assertThat(draft.body()).isEqualTo("4회초 전준우 2루 도달 · 롯데 2-5 LG");
        assertThat(draft.payload()).containsEntry("onBaseDetailSource", "snapshotDiff");
    }

    @Test
    void onBaseDraftUsesFallbackTextWhenPlayerDetailsAreUnavailable() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0, "Top 3");
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Top 3");
        GameSnapshot beforeSnapshot = snapshot(before, null, null, 1, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, null, null, 1, true, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly("ON_BASE");
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("3회초 KIA 출루 · KIA 1-0 LG");
    }

    @Test
    void outsInningAndCountOnlyChangesDoNotNotify() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0, "Top 3");
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Top 4");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 1);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 2, false, false, false, 2, 2);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void inningHalfTransitionCreatesInningChangeDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0, "Top 1");
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Bottom 1");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 1, 1, "top", "1회 초");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 1, 1, "bottom", "1회 말");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(1);
        NotificationEventDraft draft = notificationEventService.drafts.get(0);
        assertThat(draft.eventType()).isEqualTo(NotificationEventService.EVENT_INNING_CHANGED);
        assertThat(draft.eventKey()).isEqualTo("inning:%s:1:bottom".formatted(after.getId()));
        assertThat(draft.payload())
                .containsEntry("inning", 1)
                .containsEntry("inningHalf", "bottom")
                .containsEntry("inningLabel", "1회 말");
    }

    @Test
    void inningHalfTransitionLocalizesRawEnglishInningLabel() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0, "Top 1");
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Bottom 1");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 1, 1, "top", "Top 1");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 1, 1, "bottom", "Bottom 1");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        NotificationEventDraft draft = notificationEventService.drafts.get(0);
        assertThat(draft.title()).isEqualTo("1회 말");
        assertThat(draft.body()).isEqualTo("1회 말로 전환되었습니다.");
        assertThat(draft.payload()).containsEntry("inningLabel", "1회 말");
    }

    @Test
    void sameInningHalfRepeatedDoesNotCreateInningChangeDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0, "Bottom 1");
        Game after = fixtureGame(GameStatus.LIVE, 1, 0, "Bottom 1");
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 1, false, false, false, 1, 1, 1, "bottom", "1회 말");
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 1, false, false, false, 1, 1, 1, "bottom", "1회 말");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void tieToHomeLeadCreatesLeadChangeDraftWithHomeEventTeamId() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 1);
        Game after = fixtureGame(GameStatus.LIVE, 1, 2);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_LEAD_CHANGED, NotificationEventService.EVENT_SCORE_CHANGED);
        assertThat(notificationEventService.drafts.get(0).payload())
                .containsEntry(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, "lg")
                .containsEntry("previousAwayScore", 1)
                .containsEntry("previousHomeScore", 1);
    }

    @Test
    void sameLeaderScoreChangeCreatesScoreDraftWithoutLeadChangeDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 5, 2);
        Game after = fixtureGame(GameStatus.LIVE, 5, 3);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_SCORE_CHANGED);
    }

    @Test
    void scoreRegressionDoesNotCreateScoreOrLeadChangeDraft() {
        Game before = fixtureGame(GameStatus.LIVE, 6, 3);
        Game after = fixtureGame(GameStatus.LIVE, 0, 0);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void awayLeadToHomeLeadCreatesLeadChangeDraftWithHomeEventTeamId() {
        Game before = fixtureGame(GameStatus.LIVE, 3, 1);
        Game after = fixtureGame(GameStatus.LIVE, 3, 4);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts.get(0).eventType()).isEqualTo(NotificationEventService.EVENT_LEAD_CHANGED);
        assertThat(notificationEventService.drafts.get(0).payload())
                .containsEntry(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, "lg");
    }

    @Test
    void leadToTieCreatesLeadChangeDraftForScoringTeam() {
        Game before = fixtureGame(GameStatus.LIVE, 3, 1);
        Game after = fixtureGame(GameStatus.LIVE, 3, 3);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_LEAD_CHANGED, NotificationEventService.EVENT_SCORE_CHANGED);
        assertThat(notificationEventService.drafts.get(0).title()).isEqualTo("동점");
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("3회초 김타자 득점 상황, 2득점 · LG 3-3 KIA");
        assertThat(notificationEventService.drafts.get(0).payload())
                .containsEntry(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, "lg")
                .containsEntry("leadChangeReason", "TIED_GAME");
    }

    @Test
    void finalConfirmedGameIsNotRefreshedForever() {
        Game finalGame = withStatusReason(fixtureGame(GameStatus.FINAL, 4, 3), "GAME_RESULT_CK=1");
        finalGame.confirmFinal(OffsetDateTime.now(ACTIVE_KST_CLOCK));
        StubGameDetailImportService importService = new StubGameDetailImportService();
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(finalGame.getGameDate())))
                .thenReturn(List.of(finalGame));

        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(finalGame.getGameDate(), false);

        assertThat(result.candidateCount()).isZero();
        assertThat(importService.importedGameIds).isEmpty();
        verify(gameRepository, never()).findByPublicGameId(eq(finalGame.getPublicGameId()));
    }

    @Test
    void storedFinalWithNullFinalConfirmedAtOnTodayIsRevalidated() {
        Game finalGame = fixtureGame(GameStatus.FINAL, 4, 3);
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(finalGame);

        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(finalGame.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(importService.importedGameIds).containsExactly(finalGame.getPublicGameId());
    }

    @Test
    void storedFinalWithWeakSameDayConfirmationIsRevalidated() {
        Game finalGame = fixtureGame(GameStatus.FINAL, 4, 3);
        finalGame.confirmFinal(OffsetDateTime.now(ACTIVE_KST_CLOCK));
        StubGameDetailImportService importService = new StubGameDetailImportService();
        stubGameForSuccessfulDetailImport(finalGame);

        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(finalGame.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(importService.importedGameIds).containsExactly(finalGame.getPublicGameId());
    }

    @Test
    void snapshotRecoveryCreatesScoreChangedWhenOneSchedulerRunWasSkipped() {
        Game before = fixtureGame(GameStatus.LIVE, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0);
        GameSnapshot oldSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");
        GameSnapshot newSnapshot = snapshot(after, "다음타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(after));
        when(gameRepository.findByPublicGameId(eq(after.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(newSnapshot));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(after.getId()), any())).thenReturn(List.of(newSnapshot, oldSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(after.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isEqualTo(2);
        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_LEAD_CHANGED, NotificationEventService.EVENT_SCORE_CHANGED);
    }

    @Test
    void snapshotRecoveryCreatesScoreAndInningEventsFromSameSyncGap() {
        Game first = fixtureGame(GameStatus.LIVE, 0, 0, "Top 3");
        Game second = fixtureGame(GameStatus.LIVE, 1, 0, "Bottom 3");
        GameSnapshot oldSnapshot = snapshot(first, "김타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");
        GameSnapshot newSnapshot = snapshot(second, "다음타자", "박투수", 0, false, false, false, 0, 0, 3, "bottom", "Bottom 3");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(second.getGameDate())))
                .thenReturn(List.of(second));
        when(gameRepository.findByPublicGameId(eq(second.getPublicGameId()))).thenReturn(Optional.of(second));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(second.getId()))).thenReturn(Optional.of(newSnapshot));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(second.getId()), any())).thenReturn(List.of(newSnapshot, oldSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(second.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .contains(
                        NotificationEventService.EVENT_INNING_CHANGED,
                        NotificationEventService.EVENT_SCORE_CHANGED
                );
    }

    @Test
    void snapshotRecoverySkipsExistingEventKey() {
        Game before = fixtureGame(GameStatus.LIVE, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0);
        GameSnapshot oldSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");
        GameSnapshot newSnapshot = snapshot(after, "다음타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");
        String scoreKey = "score:%s:3:top:1:0".formatted(after.getId());
        String leadKey = "lead:%s:3:top:1:0:kia".formatted(after.getId());

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(after.getGameDate())))
                .thenReturn(List.of(after));
        when(gameRepository.findByPublicGameId(eq(after.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(newSnapshot));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(after.getId()), any())).thenReturn(List.of(newSnapshot, oldSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService(Set.of(scoreKey, leadKey));
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(after.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void snapshotRecoveryDistinguishesMultipleOnBaseEventsByBaseAndRunner() {
        Game game = fixtureGame(GameStatus.LIVE, 0, 0, "Top 3");
        GameSnapshot empty = snapshotWithRunners(game, "김타자", "박투수", 0, null, null, null, 3, "top", "Top 3");
        GameSnapshot first = snapshotWithRunners(game, "다음타자", "박투수", 0, "김타자", null, null, 3, "top", "Top 3");
        GameSnapshot second = snapshotWithRunners(game, "후속타자", "박투수", 0, "다음타자", "김타자", null, 3, "top", "Top 3");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.of(second));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(game.getId()), any())).thenReturn(List.of(second, first, empty));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(game.getGameDate(), false);

        List<String> onBaseKeys = notificationEventService.drafts.stream()
                .filter(draft -> NotificationEventService.EVENT_ON_BASE.equals(draft.eventType()))
                .map(NotificationEventDraft::eventKey)
                .toList();
        assertThat(onBaseKeys).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    void snapshotRecoveryDedupesSamePlateAppearanceOnBaseWhenBaseStateChanges() {
        Game game = fixtureGame(GameStatus.LIVE, 0, 0, "Bottom 1");
        GameSnapshot empty = snapshotWithRunners(game, "고승민", "원정투수", 0, null, null, null, 1, "bottom", "Bottom 1");
        GameSnapshot first = snapshotWithRunners(game, "고승민", "원정투수", 0, "고승민", null, null, 1, "bottom", "Bottom 1");
        GameSnapshot second = snapshotWithRunners(game, "고승민", "원정투수", 0, null, "고승민", null, 1, "bottom", "Bottom 1");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.of(second));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(game.getId()), any())).thenReturn(List.of(second, first, empty));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(game.getGameDate(), false);

        assertThat(notificationEventService.drafts.stream()
                .filter(draft -> NotificationEventService.EVENT_ON_BASE.equals(draft.eventType()))
                .map(NotificationEventDraft::eventKey)
                .toList())
                .containsExactly("onbase:%s:1:bottom:batter:고승민:outs:0-0".formatted(game.getId()));
    }

    @Test
    void snapshotRecoverySkipsMutableEventsWhenGameIsAlreadyFinal() {
        Game finalGame = withStatusReason(fixtureGame(GameStatus.FINAL, 3, 11, "Top 9"), "GAME_RESULT_CK=1");
        GameSnapshot empty = snapshotWithRunners(finalGame, "박민", "홈투수", 0, null, null, null, 9, "top", "Top 9");
        GameSnapshot onBase = snapshotWithRunners(finalGame, "박민", "홈투수", 0, "박민", null, null, 9, "top", "Top 9");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(finalGame.getGameDate())))
                .thenReturn(List.of(finalGame));
        when(gameRepository.findByPublicGameId(eq(finalGame.getPublicGameId()))).thenReturn(Optional.of(finalGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(finalGame.getId()))).thenReturn(Optional.of(onBase));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(finalGame.getId()), any())).thenReturn(List.of(onBase, empty));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(finalGame.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .doesNotContain(
                        NotificationEventService.EVENT_ON_BASE,
                        NotificationEventService.EVENT_SCORE_CHANGED,
                        NotificationEventService.EVENT_INNING_CHANGED
                );
    }

    @Test
    void snapshotRecoveryDoesNotDuplicateGameEndWhenEventKeyAlreadyExists() {
        Game finalGame = withStatusReason(fixtureGame(GameStatus.FINAL, 4, 2), "GAME_RESULT_CK=1");
        GameSnapshot lastSnapshot = snapshot(finalGame, "김타자", "박투수", 0, false, false, false);
        String eventKey = "end:%s:4:2:FINAL".formatted(finalGame.getId());

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(finalGame.getGameDate())))
                .thenReturn(List.of(finalGame));
        when(gameRepository.findByPublicGameId(eq(finalGame.getPublicGameId()))).thenReturn(Optional.of(finalGame));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(finalGame.getId()))).thenReturn(Optional.of(lastSnapshot));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(finalGame.getId()), any())).thenReturn(List.of(lastSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService(Set.of(eventKey));
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(finalGame.getGameDate(), false);

        assertThat(result.eventCreatedCount()).isZero();
        assertThat(notificationEventService.drafts).isEmpty();
    }

    @Test
    void diagnoseNotificationRecoveryReturnsCandidateStoredAndMissingCounts() {
        Game before = fixtureGame(GameStatus.LIVE, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0);
        GameSnapshot oldSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");
        GameSnapshot newSnapshot = snapshot(after, "다음타자", "박투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");
        String scoreKey = "score:%s:3:top:1:0".formatted(after.getId());
        String leadKey = "lead:%s:3:top:1:0:kia".formatted(after.getId());

        when(gameRepository.findByPublicGameId(eq(after.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(eq(after.getId()), any())).thenReturn(List.of(newSnapshot, oldSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService(Set.of(scoreKey, leadKey), 2);
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        var diagnosis = service.diagnoseNotificationRecovery(after.getPublicGameId());

        assertThat(diagnosis.snapshotCount()).isEqualTo(2);
        assertThat(diagnosis.candidateEventCount()).isEqualTo(2);
        assertThat(diagnosis.storedEventCount()).isEqualTo(2);
        assertThat(diagnosis.suspectedMissingEventCount()).isZero();
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubNotificationEventService notificationEventService
    ) {
        return service(clock, importService, new StubScheduleImportService(), notificationEventService, new StubTeamRankService(false));
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubScheduleImportService scheduleImportService,
            StubNotificationEventService notificationEventService
    ) {
        return service(clock, importService, scheduleImportService, notificationEventService, new StubTeamRankService(false));
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubNotificationEventService notificationEventService,
            StubTeamRankService teamRankService
    ) {
        return service(clock, importService, new StubScheduleImportService(), notificationEventService, teamRankService);
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubNotificationEventService notificationEventService,
            GameEventReadRepository gameEventReadRepository
    ) {
        return service(clock, importService, notificationEventService, gameEventReadRepository, new LiveSyncProperties());
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubNotificationEventService notificationEventService,
            GameEventReadRepository gameEventReadRepository,
            LiveSyncProperties properties
    ) {
        return new LiveGameSyncService(
                gameRepository,
                gameSnapshotRepository,
                gameEventReadRepository,
                importService,
                new StubScheduleImportService(),
                notificationEventService,
                new StubTeamRankService(false),
                properties,
                clock
        );
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubScheduleImportService scheduleImportService,
            StubNotificationEventService notificationEventService,
            StubTeamRankService teamRankService
    ) {
        return new LiveGameSyncService(
                gameRepository,
                gameSnapshotRepository,
                importService,
                scheduleImportService,
                notificationEventService,
                teamRankService,
                new LiveSyncProperties(),
                clock
        );
    }

    private NotificationEventDraft scoreChangeDraftForLotteLg(
            int awayScoreBefore,
            int homeScoreBefore,
            int awayScoreAfter,
            int homeScoreAfter,
            String previousBatter,
            String currentBatterAfter,
            List<GameEventRow> events
    ) {
        return scoreChangeDraftForLotteLg(
                awayScoreBefore,
                homeScoreBefore,
                awayScoreAfter,
                homeScoreAfter,
                previousBatter,
                currentBatterAfter,
                events,
                true,
                false,
                false
        );
    }

    private NotificationEventDraft scoreChangeDraftForLotteLg(
            int awayScoreBefore,
            int homeScoreBefore,
            int awayScoreAfter,
            int homeScoreAfter,
            String previousBatter,
            String currentBatterAfter,
            List<GameEventRow> events,
            boolean runnerOnFirstBefore,
            boolean runnerOnSecondBefore,
            boolean runnerOnThirdBefore
    ) {
        Game before = fixtureGameWithTeams(
                GameStatus.LIVE,
                awayScoreBefore,
                homeScoreBefore,
                "Top 3",
                "lotte",
                "롯데 자이언츠",
                "롯데",
                "lg",
                "LG 트윈스",
                "LG"
        );
        Game after = fixtureGameWithTeams(
                GameStatus.LIVE,
                awayScoreAfter,
                homeScoreAfter,
                "Top 3",
                "lotte",
                "롯데 자이언츠",
                "롯데",
                "lg",
                "LG 트윈스",
                "LG"
        );
        GameSnapshot beforeSnapshot = snapshot(before, previousBatter, "홈투수", 0, runnerOnFirstBefore, runnerOnSecondBefore, runnerOnThirdBefore, 0, 0, 3, "top", "Top 3");
        GameSnapshot afterSnapshot = snapshot(after, currentBatterAfter, "홈투수", 0, false, false, false, 0, 0, 3, "top", "Top 3");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(),
                notificationEventService,
                new StubGameEventReadRepository(events)
        );

        service.sync(before.getGameDate(), false);

        return notificationEventService.drafts.stream()
                .filter(candidate -> NotificationEventService.EVENT_SCORE_CHANGED.equals(candidate.eventType()))
                .findFirst()
                .orElseThrow();
    }

    private NotificationEventDraft onBaseDraftForLotteLg(
            String previousBatter,
            String firstBaseRunnerAfter,
            String secondBaseRunnerAfter,
            String thirdBaseRunnerAfter,
            List<GameEventRow> events,
            LiveSyncProperties properties
    ) {
        return onBaseDraftForLotteLg(
                previousBatter,
                firstBaseRunnerAfter,
                secondBaseRunnerAfter,
                thirdBaseRunnerAfter,
                new StubGameEventReadRepository(events),
                properties
        );
    }

    private NotificationEventDraft onBaseDraftForLotteLg(
            String previousBatter,
            String firstBaseRunnerAfter,
            String secondBaseRunnerAfter,
            String thirdBaseRunnerAfter,
            GameEventReadRepository gameEventReadRepository,
            LiveSyncProperties properties
    ) {
        Game before = fixtureGameWithTeams(
                GameStatus.LIVE,
                2,
                5,
                "Top 4",
                "lotte",
                "롯데 자이언츠",
                "롯데",
                "lg",
                "LG 트윈스",
                "LG"
        );
        Game after = fixtureGameWithTeams(
                GameStatus.LIVE,
                2,
                5,
                "Top 4",
                "lotte",
                "롯데 자이언츠",
                "롯데",
                "lg",
                "LG 트윈스",
                "LG"
        );
        GameSnapshot beforeSnapshot = snapshotWithRunners(
                before,
                previousBatter,
                "홈투수",
                0,
                null,
                null,
                null,
                4,
                "top",
                "Top 4"
        );
        GameSnapshot afterSnapshot = snapshotWithRunners(
                after,
                "다음타자",
                "홈투수",
                0,
                firstBaseRunnerAfter,
                secondBaseRunnerAfter,
                thirdBaseRunnerAfter,
                4,
                "top",
                "Top 4"
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(
                ACTIVE_KST_CLOCK,
                new StubGameDetailImportService(),
                notificationEventService,
                gameEventReadRepository,
                properties
        );

        service.sync(before.getGameDate(), false);

        return notificationEventService.drafts.stream()
                .filter(candidate -> NotificationEventService.EVENT_ON_BASE.equals(candidate.eventType()))
                .findFirst()
                .orElseThrow();
    }

    private Game fixtureGame(GameStatus status, Integer awayScore, Integer homeScore) {
        return fixtureGame(status, awayScore, homeScore, null);
    }

    private Game fixtureGame(GameStatus status, Integer awayScore, Integer homeScore, String inningState) {
        return fixtureGame(
                status,
                awayScore,
                homeScore,
                inningState,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9))
        );
    }

    private Game fixtureGame(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            OffsetDateTime scheduledAt
    ) {
        return fixtureGame(status, awayScore, homeScore, inningState, scheduledAt, false, false, null, null);
    }

    private Game cancelledFixtureGame(GameStatus status, GameCancelReason cancelReason, String rawCancelText) {
        return fixtureGame(
                status,
                null,
                null,
                null,
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                status == GameStatus.CANCELLED,
                status == GameStatus.POSTPONED,
                cancelReason,
                rawCancelText
        );
    }

    private Game withStatusReason(Game game, String statusReason) {
        game.syncDetail(
                game.getStatus(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.getInningState(),
                game.isCancelled(),
                game.isPostponed(),
                game.getCancelReason(),
                game.getRawCancelText(),
                null,
                null,
                null,
                statusReason,
                game.getSourceUpdatedAt()
        );
        return game;
    }

    private Game fixtureGame(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            OffsetDateTime scheduledAt,
            boolean isCancelled,
            boolean isPostponed,
            GameCancelReason cancelReason,
            String rawCancelText
    ) {
        Team homeTeam = new Team(UUID.randomUUID(), "lg", "LG 트윈스", "LG", "LG Twins", null);
        Team awayTeam = new Team(UUID.randomUUID(), "kia", "KIA 타이거즈", "KIA", "KIA Tigers", null);
        return new Game(
                UUID.randomUUID(),
                "20260409-LG-KIA",
                "kbo",
                "20260409HTLG0",
                LocalDate.of(2026, 4, 9),
                scheduledAt,
                "잠실",
                status,
                homeTeam,
                awayTeam,
                homeScore,
                awayScore,
                inningState,
                isCancelled,
                isPostponed,
                cancelReason,
                rawCancelText,
                null
        );
    }

    private Game fixtureGameWithTeams(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            String awayTeamCode,
            String awayTeamName,
            String awayTeamShortName,
            String homeTeamCode,
            String homeTeamName,
            String homeTeamShortName
    ) {
        Team homeTeam = new Team(UUID.randomUUID(), homeTeamCode, homeTeamName, homeTeamShortName, homeTeamName, null);
        Team awayTeam = new Team(UUID.randomUUID(), awayTeamCode, awayTeamName, awayTeamShortName, awayTeamName, null);
        return new Game(
                UUID.randomUUID(),
                "20260409-%s-%s".formatted(homeTeamCode.toUpperCase(), awayTeamCode.toUpperCase()),
                "kbo",
                "20260409%s%s0".formatted(awayTeamCode.toUpperCase(), homeTeamCode.toUpperCase()),
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                status,
                homeTeam,
                awayTeam,
                homeScore,
                awayScore,
                inningState,
                false,
                false,
                null,
                null,
                null
        );
    }

    private Game fixtureGameWithTeamsAndSchedule(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            OffsetDateTime scheduledAt,
            String awayTeamCode,
            String awayTeamName,
            String awayTeamShortName,
            String homeTeamCode,
            String homeTeamName,
            String homeTeamShortName
    ) {
        return fixtureGameWithTeamsAndSchedule(
                status,
                awayScore,
                homeScore,
                inningState,
                scheduledAt,
                awayTeamCode,
                awayTeamName,
                awayTeamShortName,
                homeTeamCode,
                homeTeamName,
                homeTeamShortName,
                "사직"
        );
    }

    private Game fixtureGameWithTeamsAndSchedule(
            GameStatus status,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            OffsetDateTime scheduledAt,
            String awayTeamCode,
            String awayTeamName,
            String awayTeamShortName,
            String homeTeamCode,
            String homeTeamName,
            String homeTeamShortName,
            String stadium
    ) {
        Team homeTeam = new Team(UUID.randomUUID(), homeTeamCode, homeTeamName, homeTeamShortName, homeTeamName, null);
        Team awayTeam = new Team(UUID.randomUUID(), awayTeamCode, awayTeamName, awayTeamShortName, awayTeamName, null);
        return new Game(
                UUID.fromString("3f121ae4-a2bc-439a-ae3e-7c3d0cd17191"),
                "20260625-%s-%s".formatted(homeTeamCode.toUpperCase(), awayTeamCode.toUpperCase()),
                "kbo",
                "20260625%s%s0".formatted(awayTeamCode.toUpperCase(), homeTeamCode.toUpperCase()),
                LocalDate.of(2026, 6, 25),
                scheduledAt,
                stadium,
                status,
                homeTeam,
                awayTeam,
                homeScore,
                awayScore,
                inningState,
                false,
                false,
                null,
                null,
                null
        );
    }

    private void stubGameForSuccessfulDetailImport(Game game) {
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(game.getGameDate())))
                .thenReturn(List.of(game));
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.empty());
    }

    private Game fixtureGameWithPublicGameId(String publicGameId, GameStatus status) {
        Team homeTeam = new Team(UUID.randomUUID(), "lg", "LG 트윈스", "LG", "LG Twins", null);
        Team awayTeam = new Team(UUID.randomUUID(), "kia", "KIA 타이거즈", "KIA", "KIA Tigers", null);
        return new Game(
                UUID.randomUUID(),
                publicGameId,
                "kbo",
                publicGameId + "-provider",
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                status,
                homeTeam,
                awayTeam,
                0,
                0,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(String instant) {
            this.instant = Instant.parse(instant);
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
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

    private GameSnapshot snapshot(
            Game game,
            String batter,
            String pitcher,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird
    ) {
        return snapshot(game, batter, pitcher, outs, runnerOnFirst, runnerOnSecond, runnerOnThird, 0, 0);
    }

    private GameSnapshot snapshot(
            Game game,
            String batter,
            String pitcher,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            Integer balls,
            Integer strikes
    ) {
        return snapshot(game, batter, pitcher, outs, runnerOnFirst, runnerOnSecond, runnerOnThird, balls, strikes, 3, "top", "Top 3");
    }

    private GameSnapshot snapshot(
            Game game,
            String batter,
            String pitcher,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            Integer balls,
            Integer strikes,
            Integer inning,
            String inningHalf,
            String inningLabel
    ) {
        return snapshotAt(
                game,
                batter,
                pitcher,
                outs,
                runnerOnFirst,
                runnerOnSecond,
                runnerOnThird,
                balls,
                strikes,
                inning,
                inningHalf,
                inningLabel,
                OffsetDateTime.now(ACTIVE_KST_CLOCK)
        );
    }

    private GameSnapshot snapshotAt(
            Game game,
            String batter,
            String pitcher,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            Integer balls,
            Integer strikes,
            Integer inning,
            String inningHalf,
            String inningLabel,
            OffsetDateTime fetchedAt
    ) {
        return new GameSnapshot(
                UUID.randomUUID(),
                game,
                inning,
                inningHalf,
                inningLabel,
                balls,
                strikes,
                outs,
                runnerOnFirst,
                runnerOnSecond,
                runnerOnThird,
                pitcher,
                batter,
                game.getHomeScore(),
                game.getAwayScore(),
                null,
                null,
                null,
                null,
                null,
                null,
                UUID.randomUUID().toString(),
                null,
                fetchedAt
        );
    }

    private GameSnapshot snapshotWithRunners(
            Game game,
            String batter,
            String pitcher,
            Integer outs,
            String firstBaseRunnerName,
            String secondBaseRunnerName,
            String thirdBaseRunnerName,
            Integer inning,
            String inningHalf,
            String inningLabel
    ) {
        boolean runnerOnFirst = firstBaseRunnerName != null;
        boolean runnerOnSecond = secondBaseRunnerName != null;
        boolean runnerOnThird = thirdBaseRunnerName != null;
        return new GameSnapshot(
                UUID.randomUUID(),
                game,
                inning,
                inningHalf,
                inningLabel,
                0,
                0,
                outs,
                runnerOnFirst,
                runnerOnSecond,
                runnerOnThird,
                firstBaseRunnerName,
                secondBaseRunnerName,
                thirdBaseRunnerName,
                runnerOnFirst ? "runner-1" : null,
                runnerOnSecond ? "runner-2" : null,
                runnerOnThird ? "runner-3" : null,
                pitcher,
                batter,
                game.getHomeScore(),
                game.getAwayScore(),
                null,
                null,
                null,
                null,
                null,
                null,
                UUID.randomUUID().toString(),
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now(ACTIVE_KST_CLOCK),
                OffsetDateTime.now(ACTIVE_KST_CLOCK)
        );
    }

    private static final class StubGameDetailImportService extends GameDetailImportService {

        private final List<String> importedGameIds = new ArrayList<>();
        private final RuntimeException failure;

        private StubGameDetailImportService() {
            this(null);
        }

        private StubGameDetailImportService(RuntimeException failure) {
            super(null, null, null, null, null, null, null, null, null, null);
            this.failure = failure;
        }

        @Override
        public GameDetailImportResult importGameDetail(String publicGameId) {
            importedGameIds.add(publicGameId);
            if (failure != null) {
                throw failure;
            }
            return null;
        }
    }

    private static final class StubScheduleImportService extends KboScheduleImportService {

        private final List<LocalDate> crawledDates = new ArrayList<>();

        private StubScheduleImportService() {
            super(null, null, null, null, null, ACTIVE_KST_CLOCK);
        }

        @Override
        public DayScheduleIngestionResult crawlDay(LocalDate date) {
            crawledDates.add(date);
            return new DayScheduleIngestionResult(date, 0, 0, 0, 0, 0, 0, 0, 0, List.of());
        }
    }

    private static final class StubNotificationEventService extends NotificationEventService {

        private final List<NotificationEventDraft> drafts = new ArrayList<>();
        private final Set<String> existingEventKeys;
        private final long storedEventCount;

        private StubNotificationEventService() {
            this(Set.of(), 0);
        }

        private StubNotificationEventService(Set<String> existingEventKeys) {
            this(existingEventKeys, existingEventKeys.size());
        }

        private StubNotificationEventService(Set<String> existingEventKeys, long storedEventCount) {
            super(null, null, null, null, ACTIVE_KST_CLOCK);
            this.existingEventKeys = new HashSet<>(existingEventKeys);
            this.storedEventCount = storedEventCount;
        }

        @Override
        public EventDeliveryResult createAndDeliver(Game game, NotificationEventDraft draft) {
            if (existingEventKeys.contains(draft.eventKey())) {
                return EventDeliveryResult.duplicate(draft.eventKey());
            }
            drafts.add(draft);
            existingEventKeys.add(draft.eventKey());
            return new EventDeliveryResult(UUID.randomUUID(), draft.eventKey(), true, 1, 0, 0);
        }

        @Override
        public boolean eventExists(String eventKey) {
            return existingEventKeys.contains(eventKey);
        }

        @Override
        public long countByGameId(UUID gameId) {
            return storedEventCount;
        }
    }

    private static final class StubGameEventReadRepository implements GameEventReadRepository {

        private final List<GameEventRow> events;

        private StubGameEventReadRepository(List<GameEventRow> events) {
            this.events = events;
        }

        @Override
        public List<GameEventRow> findRecentByGameId(UUID gameId, int limit) {
            return events;
        }
    }

    private static final class SlowGameEventReadRepository implements GameEventReadRepository {

        private final List<GameEventRow> events;
        private final long sleepMillis;

        private SlowGameEventReadRepository(List<GameEventRow> events, long sleepMillis) {
            this.events = events;
            this.sleepMillis = sleepMillis;
        }

        @Override
        public List<GameEventRow> findRecentByGameId(UUID gameId, int limit) {
            try {
                Thread.sleep(sleepMillis);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return events;
        }
    }

    private static final class StubTeamRankService extends TeamRankService {

        private final boolean fail;
        private final List<Integer> refreshedSeasons = new ArrayList<>();

        private StubTeamRankService(boolean fail) {
            super(null, null, null, ACTIVE_KST_CLOCK);
            this.fail = fail;
        }

        @Override
        public TeamRankRefreshResult refreshSeasonRankings(int season) {
            refreshedSeasons.add(season);
            if (fail) {
                throw new IllegalStateException("rank failure");
            }
            return new TeamRankRefreshResult(season, 1, 10);
        }
    }
}
