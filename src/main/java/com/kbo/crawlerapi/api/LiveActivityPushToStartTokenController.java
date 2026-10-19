package com.kbo.crawlerapi.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kbo.crawlerapi.service.LiveActivityPushToStartTokenService;
import com.kbo.crawlerapi.service.LiveActivityPushToStartTokenService.PushToStartTokenRegistrationCommand;
import com.kbo.crawlerapi.service.LiveActivityPushToStartTokenService.PushToStartTokenRegistrationResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiveActivityPushToStartTokenController {

    private final LiveActivityPushToStartTokenService tokenService;

    public LiveActivityPushToStartTokenController(LiveActivityPushToStartTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @PostMapping({"/devices/live-activities/push-to-start/register", "/live-activities/push-to-start/register"})
    public PushToStartTokenRegistrationResult register(@RequestBody PushToStartTokenRegisterRequest request) {
        try {
            return tokenService.register(new PushToStartTokenRegistrationCommand(
                    request.platform(),
                    request.environment(),
                    request.pushToStartToken(),
                    request.installationId(),
                    request.favoriteTeamId(),
                    defaultValue(request.notificationsAuthorized(), false),
                    defaultValue(request.liveActivitiesEnabled(), true),
                    defaultValue(request.liveActivityAutoStartEnabled(), true),
                    defaultValue(request.gameStartEnabled(), true),
                    defaultValue(request.favoriteTeamOnlyEnabled(), false)
            ));
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    private boolean defaultValue(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    public record PushToStartTokenRegisterRequest(
            String platform,
            String environment,
            @JsonAlias({"pushToStartToken", "pushToken"})
            String pushToStartToken,
            String installationId,
            @JsonAlias("favoriteTeamID")
            String favoriteTeamId,
            Boolean notificationsAuthorized,
            Boolean liveActivitiesEnabled,
            Boolean liveActivityAutoStartEnabled,
            Boolean gameStartEnabled,
            Boolean favoriteTeamOnlyEnabled
    ) {
    }
}
