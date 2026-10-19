package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.api.DeviceRegistrationController;
import com.kbo.crawlerapi.service.DeviceRegistrationService;
import com.kbo.crawlerapi.service.DeviceRegistrationService.DeviceRegistrationResult;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistrationRateLimitFilterTest {

    @Test
    void registrationRequestsOverLimitReturnTooManyRequests() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setRegistrationRateLimitMaxRequests(1);
        properties.setRegistrationRateLimitWindow(Duration.ofMinutes(1));
        CountingDeviceRegistrationService service = new CountingDeviceRegistrationService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DeviceRegistrationController(service))
                .addFilters(new RegistrationRequestSizeLimitFilter(properties), new RegistrationRateLimitFilter(properties))
                .build();

        String body = """
                {
                  "platform": "ios",
                  "environment": "sandbox",
                  "deviceToken": "token-123",
                  "installationId": "install-1"
                }
                """;

        mockMvc.perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());

        assertThat(service.count).isEqualTo(1);
    }

    @Test
    void changingInstallationIdDoesNotBypassRateLimit() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setRegistrationRateLimitMaxRequests(1);
        CountingDeviceRegistrationService service = new CountingDeviceRegistrationService();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DeviceRegistrationController(service))
                .addFilters(new RegistrationRequestSizeLimitFilter(properties), new RegistrationRateLimitFilter(properties))
                .build();

        mockMvc.perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("install-1")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationBody("install-2")))
                .andExpect(status().isTooManyRequests());

        assertThat(service.count).isEqualTo(1);
    }

    private String registrationBody(String installationId) {
        return """
                {
                  "platform": "ios",
                  "environment": "sandbox",
                  "deviceToken": "token-123",
                  "installationId": "%s"
                }
                """.formatted(installationId);
    }

    private static final class CountingDeviceRegistrationService extends DeviceRegistrationService {
        private int count;

        private CountingDeviceRegistrationService() {
            super(null, java.time.Clock.systemUTC());
        }

        @Override
        public DeviceRegistrationResult register(
                String platform,
                String environment,
                String deviceToken,
                String installationId,
                String favoriteTeamId,
                boolean notificationsEnabled,
                DeviceNotificationSettings settings
        ) {
            count++;
            return new DeviceRegistrationResult(
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "ios",
                    "sandbox",
                    "****",
                    true
            );
        }
    }
}
