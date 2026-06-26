package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        NotificationDevice unrelated = deviceWithSettings("ssg", "token-b", true, true, true, true, true, true, true, false);

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(relevant, unrelated));

        var result = service.createAndDeliver(game, draft());

        assertThat(result.eventCreated()).isTrue();
        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(relevant);
        assertThat(pushService.sentEvents).extracting(NotificationEvent::getEventType).containsExactly("SCORE_CHANGED");
    }

    @Test
    void globalLotteDeviceReceivesSamsungKtScoreChangedEvent() {
        Game game = fixtureGame("samsung", "kt");
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationDevice lotteDevice = deviceWithSettings("lotte", "token-lotte", true, true, true, true, true, true, false, false);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "samsung");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(lotteDevice));

        var result = service.createAndDeliver(game, draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(lotteDevice);
    }

    @Test
    void favoriteOnlyLotteDeviceDoesNotReceiveSamsungKtScoreChangedEvent() {
        Game game = fixtureGame("samsung", "kt");
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationDevice lotteDevice = deviceWithSettings("lotte", "token-lotte", true, true, true, true, true, true, true, false);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "samsung");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(lotteDevice));

        var result = service.createAndDeliver(game, draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.NO_RELEVANT_DEVICES);
    }

    @Test
    void favoriteOnlySamsungDeviceReceivesSamsungKtScoreChangedEvent() {
        Game game = fixtureGame("samsung", "kt");
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationDevice samsungDevice = deviceWithSettings("samsung", "token-samsung", true, true, true, true, true, true, true, false);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "samsung");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(samsungDevice));

        var result = service.createAndDeliver(game, draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(samsungDevice);
    }

    @Test
    void globalLotteDeviceWithScoreChangeDisabledDoesNotReceiveSamsungKtScoreChangedEvent() {
        Game game = fixtureGame("samsung", "kt");
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationDevice lotteDevice = deviceWithSettings("lotte", "token-lotte", true, false, true, true, true, true, false, false);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "samsung");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(lotteDevice));

        var result = service.createAndDeliver(game, draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED);
    }

    @Test
    void deviceWithMismatchedEnvironmentDoesNotReceiveSamsungKtScoreChangedEvent() {
        Game game = fixtureGame("samsung", "kt");
        RecordingApnsPushService pushService = new RecordingApnsPushService(
                ApnsPushService.ApnsSendResult.sentResult(),
                null,
                "production"
        );
        NotificationEventService service = service(pushService);
        NotificationDevice sandboxDevice = deviceWithSettings("lotte", "token-lotte", true, true, true, true, true, true, false, false);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "samsung");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(sandboxDevice));

        var result = service.createAndDeliver(game, draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.ENVIRONMENT_MISMATCH);
    }

    @Test
    void apnsSendRunsOutsideTransactionWhenTransactionManagerIsConfigured() {
        Game game = fixtureGame();
        TransactionAwareApnsPushService pushService = new TransactionAwareApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = new NotificationEventService(
                notificationEventRepository,
                notificationDeviceRepository,
                pushService,
                new ObjectMapper(),
                CLOCK,
                new RecordingTransactionManager()
        );
        NotificationDevice relevant = device("kia", "token-a");
        AtomicReference<NotificationEvent> saved = new AtomicReference<>();

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> {
            NotificationEvent event = invocation.getArgument(0);
            saved.set(event);
            return event;
        });
        when(notificationEventRepository.findById(any(UUID.class))).thenAnswer(invocation -> Optional.of(saved.get()));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(relevant));

        service.createAndDeliver(game, draft());

        assertThat(pushService.transactionActiveDuringSend).containsExactly(false);
    }

    @Test
    void duplicateEventIsNotSentAgain() {
        NotificationEvent existing = new NotificationEvent(UUID.randomUUID(), fixtureGame(), "SCORE_CHANGED", "event-key", "title", "body", "{}");
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));
        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.of(existing));

        var result = service.createAndDeliver(fixtureGame(), draft());

        assertThat(result.eventCreated()).isFalse();
        verify(notificationEventRepository, never()).save(any());
        verify(notificationDeviceRepository, never()).findByPlatformAndNotificationsEnabledTrue(any());
    }

    @Test
    void invalidDeviceTokenDoesNotFailWholeBatch() {
        Game game = fixtureGame();
        RecordingApnsPushService pushService = new RecordingApnsPushService(new ApnsPushService.ApnsSendResult(false, false, true, "Unregistered"));
        NotificationEventService service = service(pushService);
        NotificationDevice relevant = device("kia", "token-a");

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(relevant));

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
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(relevant));

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
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(relevant));

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
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(relevant));

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
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(disabled));

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
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(sandboxDevice));

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
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(android));

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.UNSUPPORTED_PLATFORM);
    }

    @Test
    void noRelevantDevicesSkipsWithNoRelevantDevices() {
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));

        when(notificationEventRepository.findByEventKey(eq("event-key"))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of());

        service.createAndDeliver(fixtureGame(), draft());

        assertThat(savedEvent().getDeliveryStatus()).isEqualTo("skipped");
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.NO_RELEVANT_DEVICES);
    }

    @Test
    void nonScoringAndNonOnBaseEventsAreNotSent() {
        NotificationEventService service = service(new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult()));
        NotificationEventService.NotificationEventDraft draft = new NotificationEventService.NotificationEventDraft(
                "STARTING_PITCHERS",
                "pitchers-event-key",
                "title",
                "body",
                Map.of("gameId", "game")
        );

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.eventCreated()).isFalse();
        assertThat(result.skippedCount()).isEqualTo(1);
        verify(notificationEventRepository, never()).save(any());
        verify(notificationDeviceRepository, never()).findByPlatformAndNotificationsEnabledTrue(any());
    }

    @Test
    void disabledDetailedEventSettingsSkipDelivery() {
        List<NotificationEventService.NotificationEventDraft> drafts = List.of(
                draft(NotificationEventService.EVENT_GAME_STARTED, null),
                draft(NotificationEventService.EVENT_SCORE_CHANGED, "kia"),
                draft(NotificationEventService.EVENT_LEAD_CHANGED, "kia"),
                draft(NotificationEventService.EVENT_GAME_FINAL, null),
                draft(NotificationEventService.EVENT_ON_BASE, "kia"),
                draft(NotificationEventService.EVENT_INNING_CHANGED, null)
        );
        List<NotificationDevice> disabledDevices = List.of(
                deviceWithSettings("kia", "token-game-start", false, true, true, true, true, true, false, false),
                deviceWithSettings("kia", "token-score", true, false, true, true, true, true, false, false),
                deviceWithSettings("kia", "token-lead", true, true, false, true, true, true, false, false),
                deviceWithSettings("kia", "token-game-end", true, true, true, false, true, true, false, false),
                deviceWithSettings("kia", "token-on-base", true, true, true, true, false, true, false, false),
                deviceWithSettings("kia", "token-inning", true, true, true, true, true, false, false, false)
        );

        for (int index = 0; index < drafts.size(); index++) {
            reset(notificationEventRepository, notificationDeviceRepository);
            RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
            NotificationEventService service = service(pushService);
            NotificationEventService.NotificationEventDraft draft = drafts.get(index);

            when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
            when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
            when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(disabledDevices.get(index)));

            var result = service.createAndDeliver(fixtureGame(), draft);

            assertThat(result.sentCount()).isZero();
            assertThat(result.skippedCount()).isEqualTo(1);
            assertThat(pushService.sentDevices).isEmpty();
            assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED);
        }
    }

    @Test
    void favoriteTeamOnlySkipsOpponentScoreAndOnBaseNotifications() {
        NotificationDevice device = deviceWithSettings("kia", "token-a", true, true, true, true, true, true, true, false);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "lg");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED);
    }

    @Test
    void favoriteTeamOnlyAllowsFavoriteTeamScoreNotifications() {
        NotificationDevice device = deviceWithSettings("kia", "token-a", true, true, true, true, true, true, true, false);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "kia");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(device);
    }

    @Test
    void favoriteTeamOnlySkipsOpponentLeadChangeNotifications() {
        NotificationDevice device = deviceWithSettings("kia", "token-a", true, true, true, true, true, true, true, false);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_LEAD_CHANGED, "lg");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED);
    }

    @Test
    void favoriteTeamOnlySkipsScoreNotificationsWhenEventTeamIdIsMissing() {
        NotificationDevice device = deviceWithSettings("kia", "token-a", true, true, true, true, true, true, true, false);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED);
    }

    @Test
    void muteWhenLosingAllowsScoreNotificationsWhenFavoriteTeamIsLeading() {
        Game leadingGame = fixtureGame(1, 2);
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "lg");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(leadingGame, draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(device);
    }

    @Test
    void muteWhenLosingSkipsRealtimeNotificationsWhenFavoriteTeamIsLosing() {
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "lg");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
    }

    @Test
    void muteWhenLosingSkipsScoreNotificationsWhenFavoriteTeamTiesGame() {
        Game tiedGame = fixtureGame(3, 3);
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_SCORE_CHANGED, "lg");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(tiedGame, draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
    }

    @Test
    void muteWhenLosingSkipsLeadChangeNotificationsWhenFavoriteTeamIsNotLeading() {
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_LEAD_CHANGED, "lg");

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
    }

    @Test
    void muteWhenLosingDoesNotApplyToInningChangeNotificationsWhenFavoriteTeamIsLosing() {
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_INNING_CHANGED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(device);
    }

    @Test
    void muteWhenLosingAllowsInningChangeNotificationsWhenFavoriteTeamIsTied() {
        Game tiedGame = fixtureGame(1, 1);
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_INNING_CHANGED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(tiedGame, draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(device);
    }

    @Test
    void muteWhenLosingAllowsInningChangeNotificationsWhenFavoriteTeamIsLeading() {
        Game leadingGame = fixtureGame(1, 2);
        NotificationDevice device = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_INNING_CHANGED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(device));

        var result = service.createAndDeliver(leadingGame, draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(device);
    }

    @Test
    void cancelledEventSendsToDevicesForEitherTeam() {
        Game game = fixtureGame();
        NotificationDevice awayDevice = device("kia", "token-away");
        NotificationDevice homeDevice = device("lg", "token-home");
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_GAME_CANCELLED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(awayDevice, homeDevice));

        var result = service.createAndDeliver(game, draft);

        assertThat(result.sentCount()).isEqualTo(2);
        assertThat(pushService.sentDevices).containsExactly(awayDevice, homeDevice);
        assertThat(pushService.sentEvents).extracting(NotificationEvent::getEventType)
                .containsExactly(NotificationEventService.EVENT_GAME_CANCELLED, NotificationEventService.EVENT_GAME_CANCELLED);
    }

    @Test
    void favoriteTeamOnlyFiltersUnrelatedCancelledGameDevices() {
        NotificationDevice unrelatedDevice = deviceWithSettings("ssg", "token-a", true, true, true, true, true, true, true, false);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_GAME_CANCELLED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(unrelatedDevice));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).isEmpty();
        assertThat(savedEvent().getErrorMessage()).isEqualTo(ApnsPushService.NO_RELEVANT_DEVICES);
    }

    @Test
    void muteWhenLosingDoesNotApplyToCancelledGameNotifications() {
        NotificationDevice losingFavoriteDevice = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_GAME_CANCELLED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(losingFavoriteDevice));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(losingFavoriteDevice);
    }

    @Test
    void favoriteTeamOnlyAllowsInterruptedGameNotificationsForFavoriteTeamGame() {
        NotificationDevice favoriteOnlyDevice = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, true, false);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_GAME_INTERRUPTED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(favoriteOnlyDevice));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(favoriteOnlyDevice);
    }

    @Test
    void muteWhenLosingDoesNotApplyToInterruptedGameNotifications() {
        NotificationDevice losingFavoriteDevice = deviceWithSettings("lg", "token-a", true, true, true, true, true, true, false, true);
        RecordingApnsPushService pushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult());
        NotificationEventService service = service(pushService);
        NotificationEventService.NotificationEventDraft draft = draft(NotificationEventService.EVENT_GAME_INTERRUPTED, null);

        when(notificationEventRepository.findByEventKey(eq(draft.eventKey()))).thenReturn(Optional.empty());
        when(notificationEventRepository.save(any(NotificationEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue(eq("ios"))).thenReturn(List.of(losingFavoriteDevice));

        var result = service.createAndDeliver(fixtureGame(), draft);

        assertThat(result.sentCount()).isEqualTo(1);
        assertThat(pushService.sentDevices).containsExactly(losingFavoriteDevice);
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
                NotificationEventService.EVENT_SCORE_CHANGED,
                "event-key",
                "title",
                "body",
                Map.of("gameId", "game")
        );
    }

    private NotificationEventService.NotificationEventDraft draft(String eventType, String eventTeamId) {
        java.util.HashMap<String, Object> payload = new java.util.HashMap<>();
        payload.put("gameId", "game");
        if (eventTeamId != null) {
            payload.put(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, eventTeamId);
        }
        return new NotificationEventService.NotificationEventDraft(
                eventType,
                "event-key-" + eventType + "-" + (eventTeamId == null ? "none" : eventTeamId),
                "title",
                "body",
                payload
        );
    }

    private NotificationDevice device(String favoriteTeamId, String token) {
        return new NotificationDevice(UUID.randomUUID(), "ios", token, UUID.randomUUID().toString(), favoriteTeamId, true, OffsetDateTime.now(CLOCK));
    }

    private NotificationDevice device(String platform, String environment, String favoriteTeamId, String token, boolean notificationsEnabled) {
        return new NotificationDevice(UUID.randomUUID(), platform, environment, token, UUID.randomUUID().toString(), favoriteTeamId, notificationsEnabled, OffsetDateTime.now(CLOCK));
    }

    private NotificationDevice deviceWithSettings(
            String favoriteTeamId,
            String token,
            boolean gameStartEnabled,
            boolean scoreChangeEnabled,
            boolean leadChangeEnabled,
            boolean gameEndEnabled,
            boolean onBaseEnabled,
            boolean inningChangeEnabled,
            boolean favoriteTeamOnlyEnabled,
            boolean muteWhenLosingEnabled
    ) {
        return new NotificationDevice(
                UUID.randomUUID(),
                "ios",
                "sandbox",
                token,
                UUID.randomUUID().toString(),
                favoriteTeamId,
                true,
                gameStartEnabled,
                scoreChangeEnabled,
                leadChangeEnabled,
                gameEndEnabled,
                onBaseEnabled,
                inningChangeEnabled,
                favoriteTeamOnlyEnabled,
                muteWhenLosingEnabled,
                OffsetDateTime.now(CLOCK)
        );
    }

    private NotificationEvent savedEvent() {
        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationEventRepository).save(eventCaptor.capture());
        return eventCaptor.getValue();
    }

    private Game fixtureGame() {
        return fixtureGame(1, 0);
    }

    private Game fixtureGame(String awayTeamCode, String homeTeamCode) {
        return fixtureGame(awayTeamCode, homeTeamCode, 1, 0);
    }

    private Game fixtureGame(Integer awayScore, Integer homeScore) {
        return fixtureGame("kia", "lg", awayScore, homeScore);
    }

    private Game fixtureGame(String awayTeamCode, String homeTeamCode, Integer awayScore, Integer homeScore) {
        Team homeTeam = new Team(UUID.randomUUID(), homeTeamCode, homeTeamCode.toUpperCase(), homeTeamCode.toUpperCase(), homeTeamCode, null);
        Team awayTeam = new Team(UUID.randomUUID(), awayTeamCode, awayTeamCode.toUpperCase(), awayTeamCode.toUpperCase(), awayTeamCode, null);
        return new Game(
                UUID.randomUUID(),
                "20260409-" + homeTeamCode.toUpperCase() + "-" + awayTeamCode.toUpperCase(),
                "kbo",
                "20260409" + homeTeamCode.toUpperCase() + awayTeamCode.toUpperCase(),
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.LIVE,
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

    private static class RecordingApnsPushService extends ApnsPushService {

        private final ApnsSendResult result;
        private final String readinessSkipReason;
        private final String configuredEnvironment;
        private final List<NotificationDevice> sentDevices = new java.util.ArrayList<>();
        private final List<NotificationEvent> sentEvents = new java.util.ArrayList<>();

        protected RecordingApnsPushService(ApnsSendResult result) {
            this(result, null, "sandbox");
        }

        protected RecordingApnsPushService(ApnsSendResult result, String readinessSkipReason, String configuredEnvironment) {
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

    private static final class TransactionAwareApnsPushService extends RecordingApnsPushService {

        private final List<Boolean> transactionActiveDuringSend = new java.util.ArrayList<>();

        private TransactionAwareApnsPushService(ApnsSendResult result) {
            super(result);
        }

        @Override
        public ApnsSendResult send(NotificationEvent event, NotificationDevice device) {
            transactionActiveDuringSend.add(TransactionSynchronizationManager.isActualTransactionActive());
            return super.send(event, device);
        }
    }

    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
