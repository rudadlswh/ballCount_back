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

        List<NotificationDevice> deliverableDevices = deliverableDevices(relevantTeamDevices);
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

    private List<NotificationDevice> deliverableDevices(List<NotificationDevice> devices) {
        return devices.stream()
                .filter(device -> "ios".equalsIgnoreCase(device.getPlatform()))
                .filter(NotificationDevice::isNotificationsEnabled)
                .filter(device -> apnsPushService.environmentMatches(device.getEnvironment()))
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
        return null;
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
