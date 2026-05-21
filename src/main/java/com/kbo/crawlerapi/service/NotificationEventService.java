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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationEventService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventService.class);
    public static final String EVENT_GAME_START = "GAME_START";
    public static final String EVENT_GAME_STARTED = EVENT_GAME_START;
    public static final String EVENT_SCORE_CHANGED = "SCORE_CHANGED";
    public static final String EVENT_LEAD_CHANGED = "LEAD_CHANGED";
    public static final String EVENT_GAME_END = "GAME_END";
    public static final String EVENT_GAME_FINAL = EVENT_GAME_END;
    public static final String EVENT_GAME_CANCELLED = "GAME_CANCELLED";
    public static final String EVENT_ON_BASE = "ON_BASE";
    public static final String EVENT_INNING_CHANGED = "INNING_CHANGED";
    public static final String PAYLOAD_EVENT_TEAM_ID = "eventTeamId";

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

        List<String> eventTeamIds = eventTeamIds(game);
        List<NotificationDevice> relevantTeamDevices = notificationDeviceRepository.findByFavoriteTeamIdIn(eventTeamIds)
                .stream()
                .filter(device -> isRelevant(device, game))
                .toList();

        ApnsPushService.ApnsDiagnostics diagnostics = apnsPushService.diagnostics();
        log.info(
                "[Notifications] delivery diagnostics eventId={} eventKey={} pushEnabled={} configTeamIdPresent={} configKeyIdPresent={} configBundleIdPresent={} privateKeyPathPresent={} inlinePrivateKeyPresent={} configuredEnv={} eventTeamIds={} relevantDeviceCount={}",
                event.getId(),
                draft.eventKey(),
                diagnostics.pushEnabled(),
                diagnostics.teamIdPresent(),
                diagnostics.keyIdPresent(),
                diagnostics.bundleIdPresent(),
                diagnostics.privateKeyPathPresent(),
                diagnostics.inlinePrivateKeyPresent(),
                diagnostics.configuredEnvironment(),
                eventTeamIds,
                relevantTeamDevices.size()
        );

        if (relevantTeamDevices.isEmpty()) {
            event.markDelivery("skipped", OffsetDateTime.now(applicationClock), ApnsPushService.NO_RELEVANT_DEVICES);
            return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, 1, 0);
        }

        relevantTeamDevices.forEach(device -> logDeviceDiagnostics(event, device, game));

        List<NotificationDevice> deliverableDevices = deliverableDevices(relevantTeamDevices, draft, game);
        String deviceSkipReason = deviceReadinessSkipReason(relevantTeamDevices, deliverableDevices);
        if (deviceSkipReason != null) {
            event.markDelivery("skipped", OffsetDateTime.now(applicationClock), deviceSkipReason);
            return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, relevantTeamDevices.size(), 0);
        }

        String apnsSkipReason = apnsPushService.readinessSkipReason();
        if (apnsSkipReason != null) {
            event.markDelivery("skipped", OffsetDateTime.now(applicationClock), apnsSkipReason);
            return new EventDeliveryResult(event.getId(), draft.eventKey(), true, 0, deliverableDevices.size(), 0);
        }

        int sent = 0;
        int skipped = 0;
        int failed = 0;
        String lastFailure = null;
        for (NotificationDevice device : deliverableDevices) {
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
                : "skipped";
        event.markDelivery(status, OffsetDateTime.now(applicationClock), lastFailure);
        return new EventDeliveryResult(event.getId(), draft.eventKey(), true, sent, skipped, failed);
    }

    private List<String> eventTeamIds(Game game) {
        Set<String> teamIds = new LinkedHashSet<>();
        teamIds.add(game.getAwayTeam().getTeamCode());
        teamIds.add(game.getHomeTeam().getTeamCode());
        return teamIds.stream().toList();
    }

    private boolean isRelevant(NotificationDevice device, Game game) {
        String favoriteTeamId = device.getFavoriteTeamId();
        return favoriteTeamId != null
                && (favoriteTeamId.equals(game.getHomeTeam().getTeamCode()) || favoriteTeamId.equals(game.getAwayTeam().getTeamCode()));
    }

    private List<NotificationDevice> deliverableDevices(List<NotificationDevice> devices, NotificationEventDraft draft, Game game) {
        return devices.stream()
                .filter(device -> "ios".equalsIgnoreCase(device.getPlatform()))
                .filter(NotificationDevice::isNotificationsEnabled)
                .filter(device -> apnsPushService.environmentMatches(device.getEnvironment()))
                .filter(device -> eventSettingEnabled(device, draft.eventType()))
                .filter(device -> favoriteTeamOnlyAllows(device, draft))
                .filter(device -> muteWhenLosingAllows(device, draft.eventType(), game))
                .toList();
    }

    private String deviceReadinessSkipReason(List<NotificationDevice> devices, List<NotificationDevice> deliverableDevices) {
        if (!deliverableDevices.isEmpty()) {
            return null;
        }

        List<NotificationDevice> supportedDevices = devices.stream()
                .filter(device -> "ios".equalsIgnoreCase(device.getPlatform()))
                .toList();
        if (supportedDevices.isEmpty()) {
            return ApnsPushService.UNSUPPORTED_PLATFORM;
        }

        List<NotificationDevice> enabledDevices = supportedDevices.stream()
                .filter(NotificationDevice::isNotificationsEnabled)
                .toList();
        if (enabledDevices.isEmpty()) {
            return ApnsPushService.DEVICE_NOTIFICATIONS_DISABLED;
        }

        boolean anyEnvironmentMatch = enabledDevices.stream()
                .anyMatch(device -> apnsPushService.environmentMatches(device.getEnvironment()));
        if (!anyEnvironmentMatch) {
            return ApnsPushService.ENVIRONMENT_MISMATCH;
        }
        return ApnsPushService.DEVICE_NOTIFICATION_SETTINGS_DISABLED;
    }

    private boolean eventSettingEnabled(NotificationDevice device, String eventType) {
        return switch (eventType) {
            case EVENT_GAME_START -> device.isGameStartEnabled();
            case EVENT_SCORE_CHANGED -> device.isScoreChangeEnabled();
            case EVENT_LEAD_CHANGED -> device.isLeadChangeEnabled();
            case EVENT_GAME_END -> device.isGameEndEnabled();
            case EVENT_GAME_CANCELLED -> device.isGameEndEnabled();
            case EVENT_ON_BASE -> device.isOnBaseEnabled();
            case EVENT_INNING_CHANGED -> device.isInningChangeEnabled();
            default -> false;
        };
    }

    private boolean favoriteTeamOnlyAllows(NotificationDevice device, NotificationEventDraft draft) {
        if (!device.isFavoriteTeamOnlyEnabled() || !isOpponentScopedEvent(draft.eventType())) {
            return true;
        }
        String eventTeamId = payloadText(draft, PAYLOAD_EVENT_TEAM_ID);
        return eventTeamId != null && eventTeamId.equals(device.getFavoriteTeamId());
    }

    private boolean muteWhenLosingAllows(NotificationDevice device, String eventType, Game game) {
        if (!device.isMuteWhenLosingEnabled() || !isRealtimeTeamEvent(eventType)) {
            return true;
        }
        String favoriteTeamId = device.getFavoriteTeamId();
        if (favoriteTeamId == null || game.getHomeScore() == null || game.getAwayScore() == null) {
            return false;
        }
        if (favoriteTeamId.equals(game.getHomeTeam().getTeamCode())) {
            return game.getHomeScore() > game.getAwayScore();
        }
        if (favoriteTeamId.equals(game.getAwayTeam().getTeamCode())) {
            return game.getAwayScore() > game.getHomeScore();
        }
        return false;
    }

    private boolean isOpponentScopedEvent(String eventType) {
        return EVENT_SCORE_CHANGED.equals(eventType)
                || EVENT_ON_BASE.equals(eventType)
                || EVENT_LEAD_CHANGED.equals(eventType);
    }

    private boolean isRealtimeTeamEvent(String eventType) {
        return EVENT_SCORE_CHANGED.equals(eventType)
                || EVENT_ON_BASE.equals(eventType)
                || EVENT_LEAD_CHANGED.equals(eventType);
    }

    private String payloadText(NotificationEventDraft draft, String key) {
        Object value = draft.payload().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private void logDeviceDiagnostics(NotificationEvent event, NotificationDevice device, Game game) {
        boolean favoriteTeamMatches = isRelevant(device, game);
        log.info(
                "[Notifications] device diagnostics eventId={} deviceId={} platform={} deviceEnv={} notificationsEnabled={} favoriteTeamMatches={} configuredEnv={}",
                event.getId(),
                device.getId(),
                device.getPlatform(),
                device.getEnvironment(),
                device.isNotificationsEnabled(),
                favoriteTeamMatches,
                apnsPushService.configuredEnvironment()
        );
    }

    private boolean isDeliverableEventType(String eventType) {
        return switch (eventType) {
            case EVENT_GAME_START, EVENT_SCORE_CHANGED, EVENT_LEAD_CHANGED, EVENT_GAME_END, EVENT_GAME_CANCELLED, EVENT_ON_BASE, EVENT_INNING_CHANGED -> true;
            default -> false;
        };
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
