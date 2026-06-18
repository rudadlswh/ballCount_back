package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.support.TeamCatalog;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationService.class);
    private static final int TOKEN_PREFIX_LENGTH = 8;
    private static final int MAX_DEVICE_TOKEN_LENGTH = 512;
    private static final int MAX_INSTALLATION_ID_LENGTH = 100;
    private static final int MAX_FAVORITE_TEAM_ID_LENGTH = 30;

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
        return register(platform, environment, deviceToken, installationId, favoriteTeamId, notificationsEnabled, DeviceNotificationSettings.defaults());
    }

    @Transactional
    public DeviceRegistrationResult register(
            String platform,
            String environment,
            String deviceToken,
            String installationId,
            String favoriteTeamId,
            boolean notificationsEnabled,
            DeviceNotificationSettings settings
    ) {
        String normalizedPlatform = normalizePlatform(platform);
        String normalizedEnvironment = normalizeEnvironment(environment);
        String normalizedToken = requireDeviceToken(deviceToken);
        String normalizedInstallationId = requireInstallationId(installationId);
        String normalizedFavoriteTeamId = normalizeFavoriteTeamId(favoriteTeamId);
        DeviceNotificationSettings normalizedSettings = settings == null ? DeviceNotificationSettings.defaults() : settings;
        OffsetDateTime now = OffsetDateTime.now(applicationClock);

        Optional<NotificationDevice> tokenMatchedDevice = notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(
                normalizedPlatform,
                normalizedEnvironment,
                normalizedToken
        );
        Optional<NotificationDevice> installationMatchedDevice = normalizedInstallationId == null
                ? Optional.empty()
                : notificationDeviceRepository.findByInstallationId(normalizedInstallationId);

        boolean created = installationMatchedDevice.isEmpty() && tokenMatchedDevice.isEmpty();
        NotificationDevice device = installationMatchedDevice
                .or(() -> tokenMatchedDevice)
                .orElseGet(() -> new NotificationDevice(
                        UUID.randomUUID(),
                        normalizedPlatform,
                        normalizedEnvironment,
                        normalizedToken,
                        normalizedInstallationId,
                        normalizedFavoriteTeamId,
                        notificationsEnabled,
                        normalizedSettings.gameStartEnabled(),
                        normalizedSettings.scoreChangeEnabled(),
                        normalizedSettings.leadChangeEnabled(),
                        normalizedSettings.gameEndEnabled(),
                        normalizedSettings.onBaseEnabled(),
                        normalizedSettings.inningChangeEnabled(),
                        normalizedSettings.favoriteTeamOnlyEnabled(),
                        normalizedSettings.muteWhenLosingEnabled(),
                        now
                ));
        tokenMatchedDevice
                .filter(tokenDevice -> !tokenDevice.getId().equals(device.getId()))
                .ifPresent(tokenDevice -> {
                    notificationDeviceRepository.delete(tokenDevice);
                    notificationDeviceRepository.flush();
                });

        String previousTokenPrefix = tokenPrefix(device.getDeviceToken());
        boolean tokenChanged = !normalizedToken.equals(device.getDeviceToken());
        device.update(
                normalizedPlatform,
                normalizedEnvironment,
                normalizedToken,
                normalizedInstallationId,
                normalizedFavoriteTeamId,
                notificationsEnabled,
                normalizedSettings.gameStartEnabled(),
                normalizedSettings.scoreChangeEnabled(),
                normalizedSettings.leadChangeEnabled(),
                normalizedSettings.gameEndEnabled(),
                normalizedSettings.onBaseEnabled(),
                normalizedSettings.inningChangeEnabled(),
                normalizedSettings.favoriteTeamOnlyEnabled(),
                normalizedSettings.muteWhenLosingEnabled(),
                now
        );
        notificationDeviceRepository.save(device);
        logRegistration(normalizedInstallationId, normalizedToken, tokenChanged ? previousTokenPrefix : null, normalizedFavoriteTeamId, normalizedEnvironment, created);
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
        String normalized = platform.trim().toLowerCase(Locale.ROOT);
        if (!"ios".equals(normalized)) {
            throw new IllegalArgumentException("platform must be ios");
        }
        return normalized;
    }

    private String normalizeEnvironment(String environment) {
        if (environment == null || environment.isBlank()) {
            return "sandbox";
        }
        String normalized = environment.trim().toLowerCase(Locale.ROOT);
        if (!"sandbox".equals(normalized) && !"production".equals(normalized)) {
            throw new IllegalArgumentException("environment must be sandbox or production");
        }
        return normalized;
    }

    private String requireDeviceToken(String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new IllegalArgumentException("deviceToken is required");
        }
        String normalized = deviceToken.trim();
        if (normalized.length() > MAX_DEVICE_TOKEN_LENGTH) {
            throw new IllegalArgumentException("deviceToken is too long");
        }
        return normalized;
    }

    private String requireInstallationId(String installationId) {
        if (installationId == null || installationId.isBlank()) {
            throw new IllegalArgumentException("installationId is required");
        }
        String normalized = installationId.trim();
        if (normalized.length() > MAX_INSTALLATION_ID_LENGTH) {
            throw new IllegalArgumentException("installationId is too long");
        }
        return normalized;
    }

    private String normalizeFavoriteTeamId(String favoriteTeamId) {
        String normalized = blankToNull(favoriteTeamId);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.length() > MAX_FAVORITE_TEAM_ID_LENGTH || !TeamCatalog.isSupportedTeamCode(normalized)) {
            throw new IllegalArgumentException("favoriteTeamId is invalid");
        }
        return normalized;
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

    private String tokenPrefix(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return token.substring(0, Math.min(TOKEN_PREFIX_LENGTH, token.length()));
    }

    private void logRegistration(String installationId, String token, String previousTokenPrefix, String favoriteTeamId, String environment, boolean created) {
        if (previousTokenPrefix == null) {
            log.info(
                    "device registration {} installation_id={} token_prefix={} favorite_team_id={} environment={}",
                    created ? "created" : "updated",
                    installationId,
                    tokenPrefix(token),
                    favoriteTeamId,
                    environment
            );
            return;
        }
        log.info(
                "device registration {} installation_id={} token_prefix={} previous_token_prefix={} favorite_team_id={} environment={}",
                created ? "created" : "updated",
                installationId,
                tokenPrefix(token),
                previousTokenPrefix,
                favoriteTeamId,
                environment
        );
    }

    public record DeviceRegistrationResult(
            UUID id,
            String platform,
            String environment,
            String maskedDeviceToken,
            boolean notificationsEnabled
    ) {
    }

    public record DeviceNotificationSettings(
            boolean gameStartEnabled,
            boolean scoreChangeEnabled,
            boolean leadChangeEnabled,
            boolean gameEndEnabled,
            boolean onBaseEnabled,
            boolean inningChangeEnabled,
            boolean favoriteTeamOnlyEnabled,
            boolean muteWhenLosingEnabled
    ) {
        public static DeviceNotificationSettings defaults() {
            return new DeviceNotificationSettings(
                    true,
                    true,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false
            );
        }
    }
}
