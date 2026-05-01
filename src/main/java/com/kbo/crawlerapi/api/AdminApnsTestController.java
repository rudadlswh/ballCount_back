package com.kbo.crawlerapi.api;

import com.kbo.crawlerapi.service.ApnsTestPushService;
import com.kbo.crawlerapi.service.ApnsTestPushService.ApnsTestPushCommand;
import com.kbo.crawlerapi.service.ApnsTestPushService.ApnsTestPushResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AdminApnsTestController {

    private final ApnsTestPushService apnsTestPushService;

    public AdminApnsTestController(ApnsTestPushService apnsTestPushService) {
        this.apnsTestPushService = apnsTestPushService;
    }

    @PostMapping("/admin/test/apns")
    public ApnsTestPushResult sendTestPush(@RequestBody ApnsTestPushRequest request) {
        if (!apnsTestPushService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            return apnsTestPushService.sendTestPush(new ApnsTestPushCommand(
                    request.favoriteTeamId(),
                    request.title(),
                    request.body(),
                    request.deepLink()
            ));
        } catch (IllegalArgumentException exception) {
            throw new InvalidParameterException(exception.getMessage());
        }
    }

    public record ApnsTestPushRequest(
            String favoriteTeamId,
            String title,
            String body,
            String deepLink
    ) {
    }
}
