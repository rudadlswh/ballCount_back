package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.service.DeviceRegistrationService;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DeviceRegistrationControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deviceRegisterRequestUsesDetailedNotificationDefaultsWhenFieldsAreMissing() throws Exception {
        DeviceRegistrationController.DeviceRegisterRequest request = objectMapper.readValue(
                """
                {
                  "platform": "ios",
                  "environment": "sandbox",
                  "deviceToken": "token-123",
                  "installationId": "install-1",
                  "favoriteTeamID": "lg",
                  "notificationsAuthorized": true
                }
                """,
                DeviceRegistrationController.DeviceRegisterRequest.class
        );

        var settings = request.notificationSettings();

        assertThat(settings.gameStartEnabled()).isTrue();
        assertThat(settings.scoreChangeEnabled()).isTrue();
        assertThat(settings.leadChangeEnabled()).isTrue();
        assertThat(settings.gameEndEnabled()).isTrue();
        assertThat(settings.onBaseEnabled()).isFalse();
        assertThat(settings.inningChangeEnabled()).isFalse();
        assertThat(settings.favoriteTeamOnlyEnabled()).isFalse();
        assertThat(settings.muteWhenLosingEnabled()).isFalse();
    }

    @Test
    void deviceRegisterRequestReadsDetailedNotificationSettingsWhenProvided() throws Exception {
        DeviceRegistrationController.DeviceRegisterRequest request = objectMapper.readValue(
                """
                {
                  "platform": "ios",
                  "environment": "sandbox",
                  "deviceToken": "token-123",
                  "installationId": "install-1",
                  "favoriteTeamID": "lg",
                  "notificationsAuthorized": true,
                  "gameStartEnabled": false,
                  "scoreChangeEnabled": true,
                  "leadChangeEnabled": false,
                  "gameEndEnabled": true,
                  "onBaseEnabled": true,
                  "inningChangeEnabled": true,
                  "favoriteTeamOnlyEnabled": true,
                  "muteWhenLosingEnabled": true
                }
                """,
                DeviceRegistrationController.DeviceRegisterRequest.class
        );

        var settings = request.notificationSettings();

        assertThat(settings.gameStartEnabled()).isFalse();
        assertThat(settings.scoreChangeEnabled()).isTrue();
        assertThat(settings.leadChangeEnabled()).isFalse();
        assertThat(settings.gameEndEnabled()).isTrue();
        assertThat(settings.onBaseEnabled()).isTrue();
        assertThat(settings.inningChangeEnabled()).isTrue();
        assertThat(settings.favoriteTeamOnlyEnabled()).isTrue();
        assertThat(settings.muteWhenLosingEnabled()).isTrue();
    }

    @Test
    void devicesRegisterReturnsBadRequestForInvalidBody() throws Exception {
        DeviceRegistrationService service = new DeviceRegistrationService(
                mock(NotificationDeviceRepository.class),
                Clock.systemUTC()
        );

        MockMvcBuilders.standaloneSetup(new DeviceRegistrationController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build()
                .perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "android",
                                  "environment": "sandbox",
                                  "deviceToken": "token-123",
                                  "installationId": "install-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
