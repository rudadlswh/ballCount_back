package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
                .isEqualTo("KIA 2 : 0 LG");
        assertThat(notificationEventService.drafts.get(0).body())
                .isEqualTo("KIA 득점");
        assertThat(notificationEventService.drafts.get(0).eventKey())
                .isEqualTo("game:%s:score:2-0".formatted(after.getId()))
                .doesNotContain("inning:", "batter:", "pitcher:", "result:");
    }

    @Test
    void liveScoreChangeUsesParsedScoringPlayDetailWithoutChangingDedupeKey() {
        Game before = fixtureGame(GameStatus.LIVE, 2, 2);
        Game after = fixtureGame(GameStatus.LIVE, 4, 2);
        GameSnapshot beforeSnapshot = snapshot(before, "레이예스", "홈투수", 0, false, true, false, 0, 0, 7, "top", "Top 7");
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
        assertThat(draft.eventKey()).isEqualTo("game:%s:score:4-2".formatted(after.getId()));
        assertThat(draft.title()).isEqualTo("KIA 득점");
        assertThat(draft.body()).isEqualTo("7회초 레이예스 2루타, 2득점 · KIA 4-2 LG");
        assertThat(draft.payload())
                .containsEntry("scoringBatterName", "레이예스")
                .containsEntry("scoringResultText", "2루타")
                .containsEntry("scoringRunsScored", 2)
                .containsEntry(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, "kia");
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
        assertThat(draft.eventType()).isEqualTo(NotificationEventService.EVENT_GAME_INTERRUPTED);
        assertThat(draft.eventKey()).isEqualTo("game:%s:interrupted:우천중단".formatted(after.getId()));
        assertThat(draft.title()).isEqualTo("경기 중단");
        assertThat(draft.body()).isEqualTo("KIA vs LG 경기가 우천으로 중단되었습니다.");
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
                .containsExactly(NotificationEventService.EVENT_GAME_INTERRUPTED);
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
                .isEqualTo("KIA: 이전타자 출루");
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

        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("KIA: 윤동희 출루");
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
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("KIA 출루");
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
        assertThat(draft.eventKey()).isEqualTo("game:%s:inning-change:1:bottom".formatted(after.getId()));
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
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("LG가 동점을 만들었습니다.");
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
        return new LiveGameSyncService(
                gameRepository,
                gameSnapshotRepository,
                gameEventReadRepository,
                importService,
                new StubScheduleImportService(),
                notificationEventService,
                new StubTeamRankService(false),
                new LiveSyncProperties(),
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

        private StubNotificationEventService() {
            super(null, null, null, null, ACTIVE_KST_CLOCK);
        }

        @Override
        public EventDeliveryResult createAndDeliver(Game game, NotificationEventDraft draft) {
            drafts.add(draft);
            return new EventDeliveryResult(UUID.randomUUID(), draft.eventKey(), true, 1, 0, 0);
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
