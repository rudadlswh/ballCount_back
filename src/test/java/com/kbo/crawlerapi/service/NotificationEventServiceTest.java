package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.repository.NotificationEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationEventServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-09T09:31:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private NotificationEventRepository notificationEventRepository;

    @Mock
    private NotificationDeviceRepository notificationDeviceRepository;

    @Test
    void scoreEventSendsOneApnsNotificationToRelevantDevice() {
        Game game = fixtureGame();
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationDevice relevant = device("kia", "token-a");
        NotificationDevice unrelated = device("ssg", "token-b");

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(relevant, unrelated));

        var result = service.createAndDeliver(game, draft());

        assertThat(result.eventCreated()).isTrue();
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(relevant);
        assertThat(pushService.sentEvents).extracting(NotificationEvent::getEventType).containsExactly("SCORE_CHANGED");
    }

    @Test
    void duplicateEventIsNotSentAgain() {
        NotificationEvent existing = new NotificationEvent(UUID.randomUUID(), fixtureGame(), "SCORE_CHANGED", "event-key", "title", "body", "{}");
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));
        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.of(existing));

        var result = service.createAndDeliver(fixtureGame(), draft());

        assertThat(result.eventCreated()).isFalse();
        verify(notificationEventRepository, never()).save(any());
        verify(notificationDeviceRepository, never()).findByFavoriteTeamIdIn(any());
    }

    @Test
    void invalidDeviceTokenDoesNotFailWholeBatch() {
        Game game = fixtureGame();
        RecordingApnsPushService pushService = new RecordingApnsPushService(new ApnsPushService.ApnsSendResult(false, false, true, "Unregistered"));
        NotificationEventService service = service(pushService);
        NotificationDevice relevant = device("kia", "token-a");

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(relevant));

        var result = service.createAndDeliver(game, draft());

        assertThat(result.failedCount()).isEqualTo(1);
        assertThat(relevant.isNotificationsEnabled()).isFalse();
    }

    @Test
    void configMissingSkipsWithoutFailingEventCreation() {
        Game game = fixtureGame();
        NotificationEventService service = service(new RecordingApnsPushService(
                ApnsPushService.ApnsSendResult.sentResult(),
                ApnsPushService.APNS_CONFIG_MISSING,
                "sandbox"
        ));
        NotificationDevice relevant = device("kia", "token-a");

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(relevant));

        var result = service.createAndDeliver(game, draft());

        assertThat(result.eventCreated()).isTrue();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failedCount()).isZero();
        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.APNS_CONFIG_MISSING);
    }

    @Test
    void pushDisabledSkipsWithExplicitReason() {
        NotificationEventService service = service(new RecordingApnsPushService(
                ApnsPushService.ApnsSendResult.sentResult(),
                ApnsPushService.APNS_PUSH_DISABLED,
                "sandbox"
        ));
        NotificationDevice relevant = device("kia", "token-a");

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(relevant));

        var result = service.createAndDeliver(fixtureGame(), draft());

        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.APNS_PUSH_DISABLED);
    }

    @Test
    void invalidApnsPrivateKeySkipsWithExplicitReason() {
        NotificationEventService service = service(new RecordingApnsPushService(
                ApnsPushService.ApnsSendResult.sentResult(),
                ApnsPushService.APNS_PRIVATE_KEY_INVALID,
                "sandbox"
        ));
        NotificationDevice relevant = device("kia", "token-a");

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(relevant));

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.APNS_PRIVATE_KEY_INVALID);
    }

    @Test
    void disabledRelevantDeviceSkipsWithDeviceNotificationsDisabled() {
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));
        NotificationDevice disabled = device("ios", "sandbox", "kia", "token-a", false);

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(disabled));

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATIONS_DISABLED);
    }

    @Test
    void mismatchedDeviceEnvironmentSkipsWithEnvironmentMismatch() {
        NotificationEventService service = service(new RecordingApnsPushService(
                ApnsPushService.ApnsSendResult.sentResult(),
                null,
                "production"
        ));
        NotificationDevice sandboxDevice = device("ios", "sandbox", "kia", "token-a", true);

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(sandboxDevice));

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.ENVIRONMENT_MISMATCH);
    }

    @Test
    void unsupportedPlatformSkipsWithUnsupportedPlatform() {
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));
        NotificationDevice android = device("android", "sandbox", "kia", "token-a", true);

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of(android));

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.UNSUPPORTED_PLATFORM);
    }

    @Test
    void noRelevantDevicesSkipsWithNoRelevantDevices() {
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByFavoriteTeamIdIn(eq(List.of("kia", "lg")))).thenReturn(List.of());

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.NO_RELEVANT_DEVICES);
    }

    @Test
    void nonScoringAndNonOnBaseEventsAreNotSent() {
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));
        NotificationEventService.NotificationEventDraft draft = new NotificationEventService.NotificationEventDraft(
                "GAME_FINAL",
                "final-event-key",
                "title",
                "body",
                Map.of("gameId", "game")
        );

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.eventCreated()).isFalse();
        assertThat(result.skippedCount()).isEqualTo(1);
        verify(notificationEventRepository, never()).save(any());
        verify(notificationDeviceRepository, never()).findByFavoriteTeamIdIn(any());
    }


    private NotificationEventService service(RecordingApnsPushService pushService) {
        return new NotificationEventService(
                notificationEventRepository,
                notificationDeviceRepository,
                pushService,
                new ObjectMapper(),
                CLOCK
        );
    }

    private NotificationEventService.NotificationEventDraft draft() {
        return new NotificationEventService.NotificationEventDraft(
                "SCORE_CHANGED",
                "event-key",
                "title",
                "body",
                Map.of("gameId", "game")
        );
    }

    private NotificationDevice device(String favoriteTeamId, String token) {
        return new NotificationDevice(UUID.randomUUID(), "ios", token, UUID.randomUUID().toString(), favoriteTeamId, true, OffsetDateTime.now(CLOCK));
    }

    private NotificationDevice device(String platform, String environment, String favoriteTeamId, String token, boolean notificationsEnabled) {
        return new NotificationDevice(UUID.randomUUID(), platform, environment, token, UUID.randomUUID().toString(), favoriteTeamId, notificationsEnabled, OffsetDateTime.now(CLOCK));
    }

    private NotificationEvent savedEvent() {
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationEventRepository).save(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private Game fixtureGame() {
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
                GameStatus.LIVE,
                homeTeam,
                awayTeam,
                0,
                1,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private static final class RecordingApnsPushService extends ApnsPushService {

        private final ApnsSendResult result;
        private final String readinessSkipReason;
        private final String configuredEnvironment;
        private final List<NotificationDevice> sentDevices = new java.util.ArrayList<>();
        private final List<NotificationEvent> sentEvents = new java.util.ArrayList<>();

        private RecordingApnsPushService(ApnsSendResult result) {
            this(result, null, "sandbox");
        }

        private RecordingApnsPushService(ApnsSendResult result, String readinessSkipReason, String configuredEnvironment) {
            super(new ApnsProperties(), CLOCK);
            this.result = result;
            this.readinessSkipReason = readinessSkipReason;
            this.configuredEnvironment = configuredEnvironment;
        }

        @Override
        public String readinessSkipReason() {
            return readinessSkipReason;
        }

        @Override
        public ApnsDiagnostics diagnostics() {
            return new ApnsDiagnostics(
                    readinessSkipReason == null || !ApnsPushService.APNS_PUSH_DISABLED.equals(readinessSkipReason),
                    true,
                    true,
                    true,
                    false,
                    readinessSkipReason == null || !ApnsPushService.APNS_CONFIG_MISSING.equals(readinessSkipReason),
                    configuredEnvironment
            );
        }

        @Override
        public boolean environmentMatches(String deviceEnvironment) {
            return configuredEnvironment.equalsIgnoreCase(deviceEnvironment);
        }

        @Override
        public String configuredEnvironment() {
            return configuredEnvironment;
        }

        @Override
        public ApnsSendResult send(NotificationEvent event, NotificationDevice device) {
            sentEvents.add(event);
            sentDevices.add(device);
            return result;
        }
    }
}
