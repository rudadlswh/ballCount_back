package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.repository.NotificationEventRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationEventService {

    private final NotificationEventRepository notificationEventRepository;
    private final NotificationDeviceRepository notificationDeviceRepository;
    private final ApnsPushService apnsPushService;
    private final ObjectMapper objectMapper;
    private final Clock applicationClock;

    public NotificationEventService(
            NotificationEventRepository notificationEventRepository,
            NotificationDeviceRepository notificationDeviceRepository,
            ApnsPushService apnsPushService,
            ObjectMapper objectMapper,
            Clock applicationClock
    ) {
        this.notificationEventRepository = notificationEventRepository;
        this.notificationDeviceRepository = notificationDeviceRepository;
        this.apnsPushService = apnsPushService;
        this.objectMapper = objectMapper;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public EventDeliveryResult createAndDeliver(Game game, NotificationEventDraft draft) {
        if (!isDeliverableEventType(draft.eventType())) {
            return EventDeliveryResult.skipped(draft.eventKey());
        }
        if (notificationEventRepository.findByEventKey(draft.eventKey()).isPresent()) {
            return EventDeliveryResult.duplicate(draft.eventKey());
        }

        NotificationEvent event = notificationEventRepository.save(new NotificationEvent(
                UUID.randomUUID(),
                game,
                draft.eventType(),
                draft.eventKey(),
                draft.title(),
                draft.body(),
                toJson(draft.payload())
        ));

        List<NotificationDevice> devices = notificationDeviceRepository.findByPlatformAndNotificationsEnabledTrue("ios")
                .stream()
                .filter(device -> isRelevant(device, game))
                .toList();

        if (devices.isEmpty()) {
            event.markDelivery("skipped", OffsetDateTime.now(applicationClock), "no_relevant_devices");
            return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, 1, 0);
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        String lastFailure = null;
        for (NotificationDevice device : devices) {
            ApnsPushService.ApnsSendResult result = apnsPushService.send(event, device);
            if (result.sent()) {
                sent++;
            } else if (result.skipped()) {
                skipped++;
                lastFailure = result.reason();
            } else {
                failed++;
                lastFailure = result.reason();
                if (result.invalidToken()) {
                    device.disable(OffsetDateTime.now(applicationClock));
                }
            }
        }

        String status = sent > 0 && failed == 0
                ? "sent"
                : sent > 0
                ? "partial_failed"
                : failed > 0
                ? "failed"
                : "config_missing".equals(lastFailure) ? "config_missing" : "skipped";
        event.markDelivery(status, OffsetDateTime.now(applicationClock), lastFailure);
        return new EventDeliveryResult(event.getId(), draft.eventKey(), true, sent, skipped, failed);
    }

    private boolean isRelevant(NotificationDevice device, Game game) {
        String favoriteTeamId = device.getFavoriteTeamId();
        return favoriteTeamId != null
                && (favoriteTeamId.equals(game.getHomeTeam().getTeamCode()) || favoriteTeamId.equals(game.getAwayTeam().getTeamCode()));
    }

    private boolean isDeliverableEventType(String eventType) {
        return "SCORE_CHANGED".equals(eventType) || "ON_BASE".equals(eventType);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize notification payload", exception);
        }
    }

    public record NotificationEventDraft(
            String eventType,
            String eventKey,
            String title,
            String body,
            Map<String, Object> payload
    ) {
    }

    public record EventDeliveryResult(
            UUID eventId,
            String eventKey,
            boolean eventCreated,
            int sentCount,
            int skippedCount,
            int failedCount
    ) {
        public static EventDeliveryResult duplicate(String eventKey) {
            return new EventDeliveryResult(null, eventKey, false, 0, 0, 0);
        }

        public static EventDeliveryResult skipped(String eventKey) {
            return new EventDeliveryResult(null, eventKey, false, 0, 1, 0);
        }
    }
}
