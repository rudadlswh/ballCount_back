package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceRegistrationService {

    private final NotificationDeviceRepository notificationDeviceRepository;
    private final Clock applicationClock;

    public DeviceRegistrationService(NotificationDeviceRepository notificationDeviceRepository, Clock applicationClock) {
        this.notificationDeviceRepository = notificationDeviceRepository;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public DeviceRegistrationResult register(String platform, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled) {
        return register(platform, null, deviceToken, installationId, favoriteTeamId, notificationsEnabled);
    }

    @Transactional
    public DeviceRegistrationResult register(String platform, String environment, String deviceToken, String installationId, String favoriteTeamId, boolean notificationsEnabled) {
        String normalizedPlatform = normalizePlatform(platform);
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedToken = requireDeviceToken(deviceToken);
        OffsetDateTime now = OffsetDateTime.now(applicationClock);
        NotificationDevice device = notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(normalizedPlatform, normalizedEnvironment, normalizedToken)
                .or(() -> installationId == null || installationId.isBlank()
                        ? java.util.Optional.empty()
                        : notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(normalizedPlatform, normalizedEnvironment, installationId))
                .orElseGet(() -> new NotificationDevice(UUID.randomUUID(), normalizedPlatform, normalizedEnvironment, normalizedToken, installationId, favoriteTeamId, notificationsEnabled, now));
        device.update(normalizedEnvironment, normalizedToken, blankToNull(installationId), blankToNull(favoriteTeamId), notificationsEnabled, now);
        notificationDeviceRepository.save(device);
        return new DeviceRegistrationResult(device.getId(), normalizedPlatform, normalizedEnvironment, maskToken(normalizedToken), device.isNotificationsEnabled());
    }

    @Transactional
    public void unregister(String platform, String deviceToken, String installationId) {
        unregister(platform, null, deviceToken, installationId);
    }

    @Transactional
    public void unregister(String platform, String environment, String deviceToken, String installationId) {
        String normalizedPlatform = normalizePlatform(platform);
        String normalizedEnvironment = normalizeEnvironment(environment);
        OffsetDateTime now = OffsetDateTime.now(applicationClock);
        notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(normalizedPlatform, normalizedEnvironment, requireDeviceToken(deviceToken))
                .or(() -> installationId == null || installationId.isBlank()
                        ? java.util.Optional.empty()
                        : notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(normalizedPlatform, normalizedEnvironment, installationId))
                .ifPresent(device -> device.disable(now));
    }

    private String normalizePlatform(String platform) {
        if (platform == null || platform.isBlank()) {
            return "ios";
        }
        return platform.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if ("development".equals(normalized) || "debug".equals(normalized)) {
            return "sandbox";
        }
        if ("release".equals(normalized)) {
            return "production";
        }
        if (!"sandbox".equals(normalized) && !"production".equals(normalized)) {
            throw new IllegalArgumentException("environment must be sandbox or production");
        }
        return normalized;
    }

    private String requireDeviceToken(String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new IllegalArgumentException("deviceToken is required");
        }
        return deviceToken.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String maskToken(String token) {
        if (token.length() <= 12) {
            return "****";
        }
        return token.substring(0, 6) + "..." + token.substring(token.length() - 6);
    }

    public record DeviceRegistrationResult(
            UUID id,
            String platform,
            String environment,
            String maskedDeviceToken,
            boolean notificationsEnabled
    ) {
    }
}
