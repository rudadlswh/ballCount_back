package com.kbo.crawlerapi.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.admin.AdminAuthenticationController;
import com.kbo.crawlerapi.admin.AdminAuthenticationService;
import com.kbo.crawlerapi.admin.AdminUiProperties;
import com.kbo.crawlerapi.api.DeviceRegistrationController;
import com.kbo.crawlerapi.api.AdminRankController;
import com.kbo.crawlerapi.service.DeviceRegistrationService;
import com.kbo.crawlerapi.service.DeviceRegistrationService.DeviceRegistrationResult;
import com.kbo.crawlerapi.service.TeamRankService;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import jakarta.servlet.http.HttpServletRequestWrapper;

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

    @Test
    void adminWriteRejectsPayloadLargerThan32Kb() throws Exception {
        MockMvc adminMockMvc = MockMvcBuilders.standaloneSetup(new AdminRankController(new StubTeamRankService()))
                .addFilters(new RegistrationRequestSizeLimitFilter(properties()))
                .build();

        adminMockMvc.perform(post("/admin/ranks/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"padding\":\"" + "a".repeat((32 * 1024) + 1) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    @Test
    void adminLoginPreservesFormParameters() throws Exception {
        AdminUiProperties adminProperties = new AdminUiProperties();
        adminProperties.setUsername("admin");
        adminProperties.setPassword("admin");
        MockMvc adminMockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminAuthenticationController(new AdminAuthenticationService(adminProperties))
                )
                .addFilters(new RegistrationRequestSizeLimitFilter(properties()))
                .build();

        adminMockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("username=admin&password=admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"));
    }

    @Test
    void rejectsUnknownLengthRequestWhenReadBodyExceedsLimit() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setRegistrationRequestMaxBytes(8);
        RegistrationRequestSizeLimitFilter filter = new RegistrationRequestSizeLimitFilter(properties);
        MockHttpServletRequest rawRequest = new MockHttpServletRequest("POST", "/devices/register");
        rawRequest.setContentType(MediaType.APPLICATION_JSON_VALUE);
        rawRequest.setContent("{\"a\":\"too-long\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        HttpServletRequestWrapper unknownLengthRequest = new HttpServletRequestWrapper(rawRequest) {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(unknownLengthRequest, response, (request, servletResponse) -> {
            throw new AssertionError("oversized request should not reach downstream chain");
        });

        org.assertj.core.api.Assertions.assertThat(response.getStatus()).isEqualTo(413);
    }

    private AppSecurityProperties properties() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setRegistrationRequestMaxBytes(32 * 1024);
        return properties;
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

    private static final class StubTeamRankService extends TeamRankService {

        private StubTeamRankService() {
            super(null, null, null, Clock.systemUTC());
        }

        @Override
        public TeamRankRefreshResult refreshSeasonRankings(int season) {
            return new TeamRankRefreshResult(season, 0, 0);
        }
    }
}
