package com.kbo.crawlerapi.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.kbo.crawlerapi.service.LiveActivityTokenRegistrationService;
import com.kbo.crawlerapi.service.LiveActivityTokenRegistrationService.LiveActivityTokenRegistrationCommand;
import com.kbo.crawlerapi.service.LiveActivityTokenRegistrationService.LiveActivityTokenRegistrationResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LiveActivityTokenController {

    private final LiveActivityTokenRegistrationService liveActivityTokenRegistrationService;

    public LiveActivityTokenController(LiveActivityTokenRegistrationService liveActivityTokenRegistrationService) {
        this.liveActivityTokenRegistrationService = liveActivityTokenRegistrationService;
    }

    @PostMapping({"/devices/live-activities/register", "/live-activities/register"})
    public LiveActivityTokenRegistrationResult register(@RequestBody LiveActivityTokenRegisterRequest request) {
        try {
            return liveActivityTokenRegistrationService.register(new LiveActivityTokenRegistrationCommand(
                    request.activityId(),
                    request.platform(),
                    request.environment(),
                    request.activityToken(),
                    request.installationId(),
                    request.favoriteTeamId(),
                    request.publicGameId(),
                    request.providerGameId(),
                    request.databaseId(),
                    request.stableDetailIdentity()
            ));
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    public record LiveActivityTokenRegisterRequest(
            String activityId,
            String platform,
            String environment,
            @JsonAlias({"pushToken", "activityToken"})
            String activityToken,
            String installationId,
            @JsonAlias("favoriteTeamID")
            String favoriteTeamId,
            @JsonAlias("publicGameID")
            String publicGameId,
            @JsonAlias("providerGameID")
            String providerGameId,
            @JsonAlias("databaseID")
            String databaseId,
            String stableDetailIdentity
    ) {
    }
}
