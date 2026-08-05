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
    private static final int MAX_MONITORED_GAME_ID_LENGTH = 200;

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
        return register(platform, environment, deviceToken, installationId, favoriteTeamId, notificationsEnabled, settings, null);
    }

    @Transactional
    public DeviceRegistrationResult register(
            String platform,
            String environment,
            String deviceToken,
            String installationId,
            String favoriteTeamId,
            boolean notificationsEnabled,
            DeviceNotificationSettings settings,
            String monitoredGameId
    ) {
        String normalizedPlatform = RegistrationInputNormalizer.normalizePlatform(platform);
        String normalizedEnvironment = RegistrationInputNormalizer.normalizeClientEnvironment(environment);
        String normalizedToken = RegistrationInputNormalizer.requireText(deviceToken, "deviceToken", MAX_DEVICE_TOKEN_LENGTH);
        String normalizedInstallationId = RegistrationInputNormalizer.requireText(installationId, "installationId", MAX_INSTALLATION_ID_LENGTH);
        String normalizedFavoriteTeamId = RegistrationInputNormalizer.normalizeFavoriteTeamId(favoriteTeamId, MAX_FAVORITE_TEAM_ID_LENGTH);
        String normalizedMonitoredGameId = RegistrationInputNormalizer.optionalText(monitoredGameId, "monitoredGameId", MAX_MONITORED_GAME_ID_LENGTH);
        DeviceNotificationSettings normalizedSettings = normalizeSettings(settings, normalizedFavoriteTeamId, notificationsEnabled);
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
                        normalizedSettings.rainDelayEnabled(),
                        normalizedSettings.quietHoursEnabled(),
                        normalizedSettings.quietHoursStartHour(),
                        normalizedSettings.quietHoursEndHour(),
                        normalizedSettings.authorizationStatus(),
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
                normalizedSettings.rainDelayEnabled(),
                normalizedSettings.quietHoursEnabled(),
                normalizedSettings.quietHoursStartHour(),
                normalizedSettings.quietHoursEndHour(),
                normalizedSettings.authorizationStatus(),
                now
        );
        device.updateMonitoredGameId(normalizedMonitoredGameId);
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

    private DeviceNotificationSettings normalizeSettings(DeviceNotificationSettings settings, String favoriteTeamId, boolean notificationsEnabled) {
        DeviceNotificationSettings normalized = settings == null ? DeviceNotificationSettings.defaults() : settings;
        int startHour = requireHour(normalized.quietHoursStartHour(), "quietHours.startHour");
        int endHour = requireHour(normalized.quietHoursEndHour(), "quietHours.endHour");
        String authorizationStatus = normalizeAuthorizationStatus(normalized.authorizationStatus(), notificationsEnabled);
        boolean favoriteTeamOnlyEnabled = favoriteTeamId != null && normalized.favoriteTeamOnlyEnabled();
        return new DeviceNotificationSettings(
                normalized.gameStartEnabled(),
                normalized.scoreChangeEnabled(),
                normalized.leadChangeEnabled(),
                normalized.gameEndEnabled(),
                normalized.onBaseEnabled(),
                normalized.inningChangeEnabled(),
                favoriteTeamOnlyEnabled,
                normalized.muteWhenLosingEnabled(),
                normalized.rainDelayEnabled(),
                normalized.quietHoursEnabled(),
                startHour,
                endHour,
                authorizationStatus
        );
    }

    private int requireHour(int hour, String field) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException(field + " must be between 0 and 23");
        }
        return hour;
    }

    private String normalizeAuthorizationStatus(String value, boolean notificationsEnabled) {
        if (value == null || value.isBlank()) {
            return notificationsEnabled ? "authorized" : "denied";
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "not_determined", "denied", "authorized", "provisional", "ephemeral", "unsupported" -> normalized;
            default -> throw new IllegalArgumentException("notificationAuthorizationStatus is invalid");
        };
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
            boolean muteWhenLosingEnabled,
            boolean rainDelayEnabled,
            boolean quietHoursEnabled,
            int quietHoursStartHour,
            int quietHoursEndHour,
            String authorizationStatus
    ) {
        public DeviceNotificationSettings(
                boolean gameStartEnabled,
                boolean scoreChangeEnabled,
                boolean leadChangeEnabled,
                boolean gameEndEnabled,
                boolean onBaseEnabled,
                boolean inningChangeEnabled,
                boolean favoriteTeamOnlyEnabled,
                boolean muteWhenLosingEnabled
        ) {
            this(gameStartEnabled, scoreChangeEnabled, leadChangeEnabled, gameEndEnabled, onBaseEnabled,
                    inningChangeEnabled, favoriteTeamOnlyEnabled, muteWhenLosingEnabled, true, false, 23, 7, null);
        }

        public static DeviceNotificationSettings defaults() {
            return new DeviceNotificationSettings(
                    true,
                    true,
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    23,
                    7,
                    null
            );
        }
    }
}
