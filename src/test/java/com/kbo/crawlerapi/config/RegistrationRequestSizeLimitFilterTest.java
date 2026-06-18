package com.kbo.crawlerapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.api.DeviceRegistrationController;
import com.kbo.crawlerapi.service.DeviceRegistrationService;
import com.kbo.crawlerapi.service.DeviceRegistrationService.DeviceRegistrationResult;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RegistrationRequestSizeLimitFilterTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setRegistrationRequestMaxBytes(32 * 1024);
        mockMvc = MockMvcBuilders.standaloneSetup(new DeviceRegistrationController(new StubDeviceRegistrationService()))
                .addFilters(new RegistrationRequestSizeLimitFilter(properties))
                .build();
    }

    @Test
    void devicesRegisterAllowsNormalRequest() throws Exception {
        mockMvc.perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "platform": "ios",
                                  "environment": "sandbox",
                                  "deviceToken": "token-123",
                                  "installationId": "install-1"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void devicesRegisterRejectsPayloadLargerThan32Kb() throws Exception {
        mockMvc.perform(post("/devices/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceToken\":\"" + "a".repeat((32 * 1024) + 1) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void liveActivityRegisterRejectsPayloadLargerThan32Kb() throws Exception {
        mockMvc.perform(post("/live-activities/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityToken\":\"" + "a".repeat((32 * 1024) + 1) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    private static final class StubDeviceRegistrationService extends DeviceRegistrationService {

        private StubDeviceRegistrationService() {
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
