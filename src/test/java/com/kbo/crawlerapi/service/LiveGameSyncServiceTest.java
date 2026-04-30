package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
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
    void liveScoreChangeCreatesScoringNotificationDraft() {
        Game before = fixtureGame(GameStatus.SCHEDULED, 0, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0);
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
        assertThat(notificationEventService.drafts.get(0).body())
                .isEqualTo("김타자 이 박투수 을 상대로 득점.\n1득점");
    }

    @Test
    void batterReachingBaseCreatesOnBaseDraftWithoutScoreChange() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0);
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
                .isEqualTo("홍길동 이 박투수 을 상대로 출루.");
    }

    @Test
    void onBaseDraftUsesFallbackTextWhenPlayerDetailsAreUnavailable() {
        Game before = fixtureGame(GameStatus.LIVE, 1, 0);
        Game after = fixtureGame(GameStatus.LIVE, 1, 0);
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
        assertThat(notificationEventService.drafts.get(0).body()).isEqualTo("출루.");
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
        return new LiveGameSyncService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                importService,
                notificationEventService,
                new LiveSyncProperties(),
                clock
        );
    }

    private Game fixtureGame(GameStatus status, Integer awayScore, Integer homeScore) {
        return fixtureGame(status, awayScore, homeScore, null);
    }

    private Game fixtureGame(GameStatus status, Integer awayScore, Integer homeScore, String inningState) {
        Team homeTeam = new Team(UUID.randomUUID(), "lg", "LG 트윈스", "LG", "LG Twins", null);
        Team awayTeam = new Team(UUID.randomUUID(), "kia", "KIA 타이거즈", "KIA", "KIA Tigers", null);
        return new Game(
                UUID.randomUUID(),
                "20260409-LG-KIA",
                "kbo",
                "20260409HTLG0",
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
        return new GameSnapshot(
                UUID.randomUUID(),
                game,
                3,
                "top",
                "Top 3",
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
            super(null, null, null, null, null, null, null);
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
}
