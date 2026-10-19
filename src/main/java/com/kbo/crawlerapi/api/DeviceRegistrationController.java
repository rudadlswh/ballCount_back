package com.kbo.crawlerapi.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kbo.crawlerapi.service.DeviceRegistrationService;
import com.kbo.crawlerapi.service.DeviceRegistrationService.DeviceNotificationSettings;
import com.kbo.crawlerapi.service.DeviceRegistrationService.DeviceRegistrationResult;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DeviceRegistrationController {

    private final DeviceRegistrationService deviceRegistrationService;

    public DeviceRegistrationController(DeviceRegistrationService deviceRegistrationService) {
        this.deviceRegistrationService = deviceRegistrationService;
    }

    @PostMapping("/devices/register")
    public DeviceRegistrationResult register(@RequestBody DeviceRegisterRequest request) {
        try {
            boolean notificationsEnabled = request.notificationsEnabled() == null || request.notificationsEnabled();
            if (request.monitoredGameId() == null) {
                return deviceRegistrationService.register(
                        request.platform(), request.environment(), request.deviceToken(), request.installationId(),
                        request.favoriteTeamId(), notificationsEnabled, request.notificationSettings()
                );
            }
            return deviceRegistrationService.register(
                    request.platform(), request.environment(), request.deviceToken(), request.installationId(),
                    request.favoriteTeamId(), notificationsEnabled, request.notificationSettings(), request.monitoredGameId()
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    @PostMapping("/devices/unregister")
    public void unregister(@RequestBody DeviceUnregisterRequest request) {
        try {
            deviceRegistrationService.unregister(request.platform(), request.environment(), request.deviceToken(), request.installationId());
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    public record DeviceRegisterRequest(
            String platform,
            String environment,
            String deviceToken,
            String installationId,
            @JsonAlias("favoriteTeamID")
            String favoriteTeamId,
            @JsonAlias("notificationsAuthorized")
            Boolean notificationsEnabled,
            Boolean gameStartEnabled,
            Boolean scoreChangeEnabled,
            Boolean leadChangeEnabled,
            Boolean gameEndEnabled,
            Boolean onBaseEnabled,
            Boolean inningChangeEnabled,
            @JsonAlias("favorite_team_only_enabled")
            Boolean favoriteTeamOnlyEnabled,
            Boolean muteWhenLosingEnabled,
            Boolean rainDelayEnabled,
            List<String> alertTypes,
            QuietHours quietHours,
            Boolean quietHoursEnabled,
            @JsonAlias({"authorizationStatus", "notification_authorization_status"})
            String notificationAuthorizationStatus,
            String monitoredGameId
    ) {
        public DeviceNotificationSettings notificationSettings() {
            return new DeviceNotificationSettings(
                    defaultValue(gameStartEnabled, true),
                    defaultValue(scoreChangeEnabled, true),
                    defaultValue(leadChangeEnabled, true),
                    defaultValue(gameEndEnabled, true),
                    defaultValue(onBaseEnabled, false),
                    defaultValue(inningChangeEnabled, false),
                    defaultValue(favoriteTeamOnlyEnabled, false),
                    defaultValue(muteWhenLosingEnabled, false),
                    rainDelaySetting(),
                    quietHoursEnabled == null ? quietHours != null : quietHoursEnabled,
                    quietHours == null ? 23 : quietHours.startHour(),
                    quietHours == null ? 7 : quietHours.endHour(),
                    notificationAuthorizationStatus
            );
        }

        private boolean rainDelaySetting() {
            if (rainDelayEnabled != null) {
                return rainDelayEnabled;
            }
            if (alertTypes == null) {
                return true;
            }
            return alertTypes.stream().anyMatch(value -> value != null && value.replace("_", "").equalsIgnoreCase("rainDelay"));
        }

        private static boolean defaultValue(Boolean value, boolean defaultValue) {
            return value == null ? defaultValue : value;
        }
    }

    public record QuietHours(int startHour, int endHour) {
    }

    public record DeviceUnregisterRequest(
            String platform,
            String environment,
            String deviceToken,
            String installationId
    ) {
    }
}
