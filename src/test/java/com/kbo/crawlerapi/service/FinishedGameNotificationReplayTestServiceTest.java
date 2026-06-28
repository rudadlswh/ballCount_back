package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.api.InvalidParameterException;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.repository.NotificationEventRepository;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FinishedGameNotificationReplayTestServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    private GameRepository gameRepository;
    private GameSnapshotRepository gameSnapshotRepository;
    private NotificationDeviceRepository notificationDeviceRepository;
    private RecordingApnsPushService apnsPushService;
    private FinishedGameNotificationReplayTestService service;
    private Game game;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameSnapshotRepository = mock(GameSnapshotRepository.class);
        notificationDeviceRepository = mock(NotificationDeviceRepository.class);
        apnsPushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService notificationEventService = new NotificationEventService(
                mock(NotificationEventRepository.class),
                notificationDeviceRepository,
                apnsPushService,
                new ObjectMapper(),
                CLOCK
        );
        LiveGameSyncService liveGameSyncService = new LiveGameSyncService(
                gameRepository,
                gameSnapshotRepository,
                null,
                null,
                notificationEventService,
                null,
                new LiveSyncProperties(),
                CLOCK
        );
        service = new FinishedGameNotificationReplayTestService(
                gameRepository,
                gameSnapshotRepository,
                notificationDeviceRepository,
                liveGameSyncService,
                notificationEventService,
                apnsPushService
        );
        game = finalGame(GameStatus.FINAL);
    }

    @Test
    void missingInstallationIdIsRejected() {
        assertThatThrownBy(() -> service.replay(command(null, false, 20, List.of("SCORE_CHANGED"))))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("installationId is required");
    }

    @Test
    void nonFinishedGameIsRejected() {
        Game liveGame = finalGame(GameStatus.LIVE);
        when(gameRepository.findByPublicGameId(eq(liveGame.getPublicGameId()))).thenReturn(Optional.of(liveGame));

        assertThatThrownBy(() -> service.replay(command("install-1", false, 20, List.of("SCORE_CHANGED"))))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("only finished games can be replayed");
    }

    @Test
    void insufficientSnapshotsAreRejected() {
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findReplaySnapshotsByGameId(eq(game.getId()))).thenReturn(List.of(snapshot(0, 0, 1, "top", 0, false, false, false)));

        assertThatThrownBy(() -> service.replay(command("install-1", false, 20, List.of("SCORE_CHANGED"))))
                .isInstanceOf(InvalidParameterException.class)
                .hasMessage("at least 2 game snapshots are required");
    }

    @Test
    void dryRunDoesNotSendApns() {
        arrangeReplay(List.of("SCORE_CHANGED"), List.of(device("install-1"), device("install-2")));

        var result = service.replay(command("install-1", true, 20, List.of("SCORE_CHANGED")));

        assertThat(result.dryRun()).isTrue();
        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.attemptedCount()).isZero();
        assertThat(result.events().get(0).deliveryStatus()).isEqualTo("dry_run");
        assertThat(apnsPushService.sentDevices).isEmpty();
    }

    @Test
    void sendsOnlyToRequestedInstallationId() {
        NotificationDevice target = device("install-1");
        arrangeReplay(List.of("SCORE_CHANGED"), List.of(target));

        var result = service.replay(command("install-1", false, 20, List.of("SCORE_CHANGED")));

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(apnsPushService.sentDevices).containsExactly(target);
        verify(notificationDeviceRepository).findByInstallationId("install-1");
        verify(notificationDeviceRepository, never()).findByPlatformAndNotificationsEnabledTrue(eq("ios"));
        verify(notificationDeviceRepository, never()).findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue(eq("ios"), eq("lotte"));
    }

    @Test
    void maxEventsLimitIsApplied() {
        arrangeReplay(List.of("GAME_START", "SCORE_CHANGED", "GAME_END"), List.of(device("install-1")));

        var result = service.replay(command("install-1", false, 1, List.of("GAME_START", "SCORE_CHANGED", "GAME_END")));

        assertThat(result.generatedCount()).isEqualTo(1);
        assertThat(result.events()).hasSize(1);
        assertThat(apnsPushService.sentDevices).hasSize(1);
    }

    @Test
    void titleHasTestPrefix() {
        arrangeReplay(List.of("SCORE_CHANGED"), List.of(device("install-1")));

        var result = service.replay(command("install-1", false, 20, List.of("SCORE_CHANGED")));

        assertThat(result.events().get(0).title()).startsWith("[테스트] ");
        assertThat(apnsPushService.sentEvents.get(0).getTitle()).startsWith("[테스트] ");
    }

    @Test
    void disabledDeviceIsSkipped() {
        arrangeReplay(List.of("SCORE_CHANGED"), List.of(disabledDevice("install-1")));

        var result = service.replay(command("install-1", false, 20, List.of("SCORE_CHANGED")));

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.events().get(0).reason()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATIONS_DISABLED);
        assertThat(apnsPushService.sentDevices).isEmpty();
    }

    private void arrangeReplay(List<String> eventTypes, List<NotificationDevice> devices) {
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findReplaySnapshotsByGameId(eq(game.getId()))).thenReturn(List.of(
                snapshot(0, 0, 1, "top", 1, false, false, false),
                snapshot(1, 0, 1, "bottom", 1, false, false, false),
                snapshot(1, 0, 2, "top", 0, false, false, false)
        ));
        when(notificationDeviceRepository.findByInstallationId(eq("install-1"))).thenReturn(devices.stream()
                .filter(device -> "install-1".equals(device.getInstallationId()))
                .toList());
    }

    private ReplayFinishedGameNotificationTestCommand command(String installationId, boolean dryRun, Integer maxEvents, List<String> eventTypes) {
        return new ReplayFinishedGameNotificationTestCommand(
                null,
                game.getPublicGameId(),
                null,
                installationId,
                eventTypes,
                maxEvents,
                dryRun
        );
    }

    private Game finalGame(GameStatus status) {
        Team homeTeam = new Team(UUID.randomUUID(), "lotte", "롯데", "롯데", "Lotte", null);
        Team awayTeam = new Team(UUID.randomUUID(), "ssg", "SSG", "SSG", "SSG", null);
        Game fixture = new Game(
                UUID.randomUUID(),
                "20260617-SSG-LOT",
                "kbo",
                "20260617LTSK0",
                LocalDate.of(2026, 6, 17),
                OffsetDateTime.of(2026, 6, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "사직",
                status,
                homeTeam,
                awayTeam,
                1,
                0,
                null,
                false,
                false,
                null,
                null,
                null
        );
        if (status == GameStatus.FINAL) {
            fixture.syncDetail(GameStatus.FINAL, 1, 0, "경기종료", false, false, null, null, null, null, null, "GAME_RESULT_CK=1", null);
            fixture.confirmFinal(OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.ofHours(9)));
        }
        return fixture;
    }

    private GameSnapshot snapshot(
            int homeScore,
            int awayScore,
            int inning,
            String inningHalf,
            int outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird
    ) {
        OffsetDateTime fetchedAt = OffsetDateTime.of(2026, 6, 17, 18, 30 + inning, 0, 0, ZoneOffset.ofHours(9));
        return new GameSnapshot(
                UUID.randomUUID(),
                game,
                inning,
                inningHalf,
                null,
                0,
                0,
                outs,
                runnerOnFirst,
                runnerOnSecond,
                runnerOnThird,
                null,
                null,
                "타자",
                null,
                null,
                null,
                "투수",
                "타자",
                homeScore,
                awayScore,
                null,
                null,
                null,
                null,
                null,
                null,
                "hash-" + UUID.randomUUID(),
                null,
                null,
                null,
                null,
                null,
                fetchedAt,
                fetchedAt
        );
    }

    private NotificationDevice device(String installationId) {
        return new NotificationDevice(
                UUID.randomUUID(),
                "ios",
                "sandbox",
                "token-" + installationId,
                installationId,
                "lotte",
                true,
                true,
                true,
                true,
                true,
                true,
                true,
                false,
                false,
                OffsetDateTime.now(CLOCK)
        );
    }

    private NotificationDevice disabledDevice(String installationId) {
        return new NotificationDevice(
                UUID.randomUUID(),
                "ios",
                "sandbox",
                "token-" + installationId,
                installationId,
                "lotte",
                false,
                OffsetDateTime.now(CLOCK)
        );
    }

    private static final class RecordingApnsPushService extends ApnsPushService {

        private final ApnsSendResult result;
        private final List<NotificationDevice> sentDevices = new java.util.ArrayList<>();
        private final List<NotificationEvent> sentEvents = new java.util.ArrayList<>();

        private RecordingApnsPushService(ApnsSendResult result) {
            super(new ApnsProperties(), CLOCK);
            this.result = result;
        }

        @Override
        public ApnsSendResult send(NotificationEvent event, NotificationDevice device) {
            sentEvents.add(event);
            sentDevices.add(device);
            return result;
        }

        @Override
        public boolean environmentMatches(String deviceEnvironment) {
            return "sandbox".equalsIgnoreCase(deviceEnvironment);
        }

        @Override
        public String configuredEnvironment() {
            return "sandbox";
        }
    }
}
