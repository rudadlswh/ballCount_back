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
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;
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

@ExtendWith(MockitoExtension.class)
class LiveGameSyncServiceTest {

    private static final Clock ACTIVE_KST_CLOCK = Clock.fixed(
            Instant.parse("2026-04-09T09:31:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Mock
    private LineScoreRepository lineScoreRepository;

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
    void gameExactlyFourHoursBeforeStartIsEligibleOutsideActiveWindow() {
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
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(importService.importedGameIds).containsExactly(game.getPublicGameId());
    }

    @Test
    void gameThreeHoursAndFiftyNineMinutesBeforeStartIsEligibleOutsideActiveWindow() {
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
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId()))).thenReturn(Optional.empty());

        LiveGameSyncService service = service(clock, importService, new StubNotificationEventService());

        LiveGameSyncService.LiveSyncSummary result = service.sync(game.getGameDate(), false);

        assertThat(result.candidateCount()).isEqualTo(1);
        assertThat(importService.importedGameIds).containsExactly(game.getPublicGameId());
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
    }

    @Test
    void scheduledToLiveTransitionCreatesGameStartDraftOnce() {
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 0, 0);
        GameSnapshot snapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
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
        Game after = fixtureGame(GameStatus.FINAL, 4, 2);
        GameSnapshot beforeSnapshot = snapshot(before, "김타자", "박투수", 0, false, false, false);
        GameSnapshot afterSnapshot = snapshot(after, "김타자", "박투수", 0, false, false, false);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.of(beforeSnapshot));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.of(afterSnapshot));
        when(lineScoreRepository.countByGame_Id(eq(after.getId()))).thenReturn(1L);

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
    }

    @Test
    void scheduledToCancelledTransitionCreatesGameCancelledDraftOnce() {
        Game before = fixtureGame(GameStatus.SCHEDULED, null, null);
        Game after = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());

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
        Game after = cancelledFixtureGame(GameStatus.CANCELLED, GameCancelReason.RAIN, "우천취소");

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
    void scheduledToPostponedTransitionCreatesGameCancelledDraftWithPostponedBody() {
        Game before = fixtureGame(GameStatus.SCHEDULED, null, null);
        Game after = cancelledFixtureGame(GameStatus.POSTPONED, null, "순연");

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        service.sync(before.getGameDate(), false);

        assertThat(notificationEventService.drafts)
                .extracting(NotificationEventDraft::eventType)
                .containsExactly(NotificationEventService.EVENT_GAME_CANCELLED);
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("KIA vs LG 경기가 순연되었습니다. (잠실, 18:30)");
    }

    @Test
    void liveToFinalTransitionTriggersTeamRankRefresh() {
        Game before = fixtureGame(GameStatus.LIVE, 3, 2);
        Game after = fixtureGame(GameStatus.FINAL, 4, 2);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());
        when(lineScoreRepository.countByGame_Id(eq(after.getId()))).thenReturn(1L);

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
        Game after = fixtureGame(GameStatus.FINAL, 4, 2);

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(before.getGameDate())))
                .thenReturn(List.of(before));
        when(gameRepository.findByPublicGameId(eq(before.getPublicGameId()))).thenReturn(Optional.of(after));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(before.getId()))).thenReturn(Optional.empty());
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(after.getId()))).thenReturn(Optional.empty());
        when(lineScoreRepository.countByGame_Id(eq(after.getId()))).thenReturn(1L);

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
        when(lineScoreRepository.countByGame_Id(eq(after.getId()))).thenReturn(1L);

        StubNotificationEventService notificationEventService = new StubNotificationEventService();
        LiveGameSyncService service = service(ACTIVE_KST_CLOCK, new StubGameDetailImportService(), notificationEventService);

        LiveGameSyncService.LiveSyncSummary result = service.sync(before.getGameDate(), false);

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
        Game finalGame = fixtureGame(GameStatus.FINAL, 4, 3);
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

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubNotificationEventService notificationEventService
    ) {
        return service(clock, importService, notificationEventService, new StubTeamRankService(false));
    }

    private LiveGameSyncService service(
            Clock clock,
            StubGameDetailImportService importService,
            StubNotificationEventService notificationEventService,
            StubTeamRankService teamRankService
    ) {
        return new LiveGameSyncService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                importService,
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

        private StubGameDetailImportService() {
            super(null, null, null, null, null, null, null, null, null, null);
        }

        @Override
        public GameDetailImportResult importGameDetail(String publicGameId) {
            importedGameIds.add(publicGameId);
            return null;
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
