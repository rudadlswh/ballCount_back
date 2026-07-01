package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.support.RegistrationInputNormalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(DeviceRegistrationService.class);
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
        String normalizedPlatform = RegistrationInputNormalizer.normalizePlatform(platform);
        String normalizedEnvironment = RegistrationInputNormalizer.normalizeClientEnvironment(environment);
        String normalizedToken = RegistrationInputNormalizer.requireText(deviceToken, "deviceToken", MAX_DEVICE_TOKEN_LENGTH);
        String normalizedInstallationId = RegistrationInputNormalizer.requireText(installationId, "installationId", MAX_INSTALLATION_ID_LENGTH);
        String normalizedFavoriteTeamId = RegistrationInputNormalizer.normalizeFavoriteTeamId(favoriteTeamId, MAX_FAVORITE_TEAM_ID_LENGTH);
        DeviceNotificationSettings normalizedSettings = normalizeSettings(settings, normalizedFavoriteTeamId);
        OffsetDateTime now = OffsetDateTime.now(applicationClock);

        Optional<NotificationDevice> tokenMatchedDevice = notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(
                normalizedPlatform,
                normalizedEnvironment,
                normalizedToken
        );
        Optional<NotificationDevice> installationMatchedDevice = normalizedInstallationId == null
                ? Optional.empty()
                : notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(
                        normalizedPlatform,
                        normalizedEnvironment,
                        normalizedInstallationId
                );

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

        boolean tokenChanged = !normalizedToken.equals(device.getDeviceToken());
        String previousTokenHash = tokenChanged ? RegistrationInputNormalizer.tokenFingerprint(device.getDeviceToken()) : null;
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
        logRegistration(normalizedToken, previousTokenHash, normalizedEnvironment, created);
        return new DeviceRegistrationResult(device.getId(), normalizedPlatform, normalizedEnvironment, maskToken(normalizedToken), device.isNotificationsEnabled());
    }

    @Transactional
    public void unregister(String platform, String deviceToken, String installationId) {
        unregister(platform, null, deviceToken, installationId);
    }

    @Transactional
    public void unregister(String platform, String environment, String deviceToken, String installationId) {
        String normalizedPlatform = RegistrationInputNormalizer.normalizePlatform(platform);
        String normalizedEnvironment = RegistrationInputNormalizer.normalizeClientEnvironment(environment);
        String normalizedInstallationId = RegistrationInputNormalizer.optionalText(installationId, "installationId", MAX_INSTALLATION_ID_LENGTH);
        OffsetDateTime now = OffsetDateTime.now(applicationClock);
        notificationDeviceRepository.findByPlatformAndEnvironmentAndDeviceToken(normalizedPlatform, normalizedEnvironment, RegistrationInputNormalizer.requireText(deviceToken, "deviceToken", MAX_DEVICE_TOKEN_LENGTH))
                .or(() -> normalizedInstallationId == null
                        ? java.util.Optional.empty()
                        : notificationDeviceRepository.findByPlatformAndEnvironmentAndInstallationId(normalizedPlatform, normalizedEnvironment, normalizedInstallationId))
                .ifPresent(device -> device.disable(now));
    }

    private DeviceNotificationSettings normalizeSettings(DeviceNotificationSettings settings, String favoriteTeamId) {
        DeviceNotificationSettings normalized = settings == null ? DeviceNotificationSettings.defaults() : settings;
        if (favoriteTeamId != null || !normalized.favoriteTeamOnlyEnabled()) {
            return normalized;
        }
        return new DeviceNotificationSettings(
                normalized.gameStartEnabled(),
                normalized.scoreChangeEnabled(),
                normalized.leadChangeEnabled(),
                normalized.gameEndEnabled(),
                normalized.onBaseEnabled(),
                normalized.inningChangeEnabled(),
                false,
                normalized.muteWhenLosingEnabled()
        );
    }

    private String maskToken(String token) {
        return "****";
    }

    private void logRegistration(String token, String previousTokenHash, String environment, boolean created) {
        if (previousTokenHash == null) {
            log.info(
                    "device registration {} token_hash={} environment={}",
                    created ? "created" : "updated",
                    RegistrationInputNormalizer.tokenFingerprint(token),
                    environment
            );
            return;
        }
        log.info(
                "device registration {} token_hash={} previous_token_hash={} environment={}",
                created ? "created" : "updated",
                RegistrationInputNormalizer.tokenFingerprint(token),
                previousTokenHash,
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
