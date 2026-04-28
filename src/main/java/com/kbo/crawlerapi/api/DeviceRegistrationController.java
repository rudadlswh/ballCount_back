package com.kbo.crawlerapi.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kbo.crawlerapi.service.DeviceRegistrationService;
import com.kbo.crawlerapi.service.DeviceRegistrationService.DeviceRegistrationResult;
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
            return deviceRegistrationService.register(
                    request.platform(),
                    request.deviceToken(),
                    request.installationId(),
                    request.favoriteTeamId(),
                    request.notificationsEnabled() == null || request.notificationsEnabled()
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    @PostMapping("/devices/unregister")
    public void unregister(@RequestBody DeviceUnregisterRequest request) {
        try {
            deviceRegistrationService.unregister(request.platform(), request.deviceToken(), request.installationId());
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    public record DeviceRegisterRequest(
            String platform,
            String deviceToken,
            String installationId,
            @JsonAlias("favoriteTeamID")
            String favoriteTeamId,
            @JsonAlias("notificationsAuthorized")
            Boolean notificationsEnabled
    ) {
    }

    public record DeviceUnregisterRequest(
            String platform,
            String deviceToken,
            String installationId
    ) {
    }
}
