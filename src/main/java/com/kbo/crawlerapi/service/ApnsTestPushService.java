package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApnsTestPushService {

    private static final Logger log = LoggerFactory.getLogger(ApnsTestPushService.class);

    private final NotificationDeviceRepository notificationDeviceRepository;
    private final ApnsPushService apnsPushService;
    private final ApnsProperties apnsProperties;
    private final ObjectMapper objectMapper;
    private final Environment environment;
    private final Clock applicationClock;

    public ApnsTestPushService(
            NotificationDeviceRepository notificationDeviceRepository,
            ApnsPushService apnsPushService,
            ApnsProperties apnsProperties,
            ObjectMapper objectMapper,
            Environment environment,
            Clock applicationClock
    ) {
        this.notificationDeviceRepository = notificationDeviceRepository;
        this.apnsPushService = apnsPushService;
        this.apnsProperties = apnsProperties;
        this.objectMapper = objectMapper;
        this.environment = environment;
        this.applicationClock = applicationClock;
    }

    public boolean isEnabled() {
        if (apnsProperties.isTestEnabled()) {
            return true;
        }
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile) || "dev".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    @Transactional(readOnly = true)
    public ApnsTestPushResult sendTestPush(ApnsTestPushCommand command) {
        if (!isEnabled()) {
            throw new IllegalStateException("APNs test endpoint is disabled");
        }

        String favoriteTeamId = requireText(command.favoriteTeamId(), "favoriteTeamId");
        String title = defaultText(command.title(), "KBO Score test");
        String body = defaultText(command.body(), "APNs manual test");
        List<NotificationDevice> matchedDevices = notificationDeviceRepository
                .findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue("ios", favoriteTeamId)
                .stream()
                .filter(device -> "ios".equalsIgnoreCase(device.getPlatform()))
                .filter(NotificationDevice::isNotificationsEnabled)
                .filter(device -> favoriteTeamId.equals(device.getFavoriteTeamId()))
                .toList();

        NotificationEvent event = new NotificationEvent(
                UUID.randomUUID(),
                null,
                "APNS_TEST",
                "apns-test-" + UUID.randomUUID(),
                title,
                body,
                payload(command.deepLink(), favoriteTeamId)
        );

        int sent = 0;
        int failed = 0;
        List<String> failureReasons = new ArrayList<>();
        Map<String, Integer> failureReasonCounts = new LinkedHashMap<>();
        for (NotificationDevice device : matchedDevices) {
            log.info(
                    "[APNs Test] sending test push deviceId={} favoriteTeamId={} deviceEnv={} configuredEnv={}",
                    device.getId(),
                    favoriteTeamId,
                    device.getEnvironment(),
                    apnsPushService.configuredEnvironment()
            );
            ApnsPushService.ApnsSendResult result = apnsPushService.send(event, device);
            if (result.sent()) {
                sent++;
            } else {
                failed++;
                String reason = result.reason() == null || result.reason().isBlank() ? "unknown" : result.reason();
                failureReasons.add(reason);
                failureReasonCounts.merge(reason, 1, Integer::sum);
            }
        }

        return new ApnsTestPushResult(
                matchedDevices.size(),
                matchedDevices.size(),
                sent,
                failed,
                failureReasons,
                failureReasonCounts
        );
    }

    private String payload(String deepLink, String favoriteTeamId) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "type", "APNS_TEST",
                    "deepLink", defaultText(deepLink, "kboscore://test"),
                    "favoriteTeamId", favoriteTeamId,
                    "sentAt", OffsetDateTime.now(applicationClock).toString()
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize APNs test payload", exception);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String defaultText(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    public record ApnsTestPushCommand(
            String favoriteTeamId,
            String title,
            String body,
            String deepLink
    ) {
    }

    public record ApnsTestPushResult(
            int matchedDeviceCount,
            int attemptedCount,
            int sentCount,
            int failedCount,
            List<String> failureReasons,
            Map<String, Integer> failureReasonCounts
    ) {
    }
}
