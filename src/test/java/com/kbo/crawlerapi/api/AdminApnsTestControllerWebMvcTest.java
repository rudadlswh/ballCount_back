package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.domain.NotificationDevice;
import com.kbo.crawlerapi.domain.NotificationEvent;
import com.kbo.crawlerapi.repository.NotificationDeviceRepository;
import com.kbo.crawlerapi.service.ApnsPushService;
import com.kbo.crawlerapi.service.ApnsTestPushService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminApnsTestControllerWebMvcTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-04-09T09:31:00Z"), ZoneId.of("Asia/Seoul"));

    private NotificationDeviceRepository notificationDeviceRepository;
    private ApnsProperties apnsProperties;
    private RecordingApnsPushService apnsPushService;
    private MockEnvironment environment;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        notificationDeviceRepository = mock(NotificationDeviceRepository.class);
        apnsProperties = new ApnsProperties();
        apnsProperties.setTestEnabled(true);
        apnsPushService = new RecordingApnsPushService(ApnsPushService.ApnsSendResult.sentResult(), "sandbox");
        environment = new MockEnvironment();
        ApnsTestPushService service = new ApnsTestPushService(
                notificationDeviceRepository,
                apnsPushService,
                apnsProperties,
                new ObjectMapper(),
                environment,
                CLOCK
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminApnsTestController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void endpointDisabledByDefault() throws Exception {
        apnsProperties.setTestEnabled(false);

        mockMvc.perform(post("/admin/test/apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ssg")))
                .andExpect(status().isNotFound());
    }

    @Test
    void endpointFindsMatchingEnabledSandboxDevices() throws Exception {
        NotificationDevice matching = device("ios", "sandbox", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue(eq("ios"), eq("ssg")))
                .thenReturn(List.of(matching));

        mockMvc.perform(post("/admin/test/apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ssg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedDeviceCount").value(1))
                .andExpect(jsonPath("$.attemptedCount").value(1))
                .andExpect(jsonPath("$.sentCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0));

        assertThat(apnsPushService.sentDevices).containsExactly(matching);
    }

    @Test
    void endpointIgnoresDisabledDevices() throws Exception {
        NotificationDevice disabled = device("ios", "sandbox", "ssg", false);
        when(notificationDeviceRepository.findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue(eq("ios"), eq("ssg")))
                .thenReturn(List.of(disabled));

        mockMvc.perform(post("/admin/test/apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ssg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedDeviceCount").value(0))
                .andExpect(jsonPath("$.attemptedCount").value(0))
                .andExpect(jsonPath("$.sentCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(0));

        assertThat(apnsPushService.sentDevices).isEmpty();
    }

    @Test
    void endpointSendsToProductionDeviceWhenConfiguredForSandbox() throws Exception {
        NotificationDevice productionDevice = device("ios", "production", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue(eq("ios"), eq("ssg")))
                .thenReturn(List.of(productionDevice));

        mockMvc.perform(post("/admin/test/apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ssg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedDeviceCount").value(1))
                .andExpect(jsonPath("$.attemptedCount").value(1))
                .andExpect(jsonPath("$.sentCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(0));

        assertThat(apnsPushService.sentDevices).containsExactly(productionDevice);
    }

    @Test
    void endpointReturnsClearFailureReasonWhenApnsFails() throws Exception {
        apnsPushService.result = new ApnsPushService.ApnsSendResult(false, false, false, ApnsPushService.APNS_INVALID_PROVIDER_TOKEN);
        NotificationDevice matching = device("ios", "sandbox", "ssg", true);
        when(notificationDeviceRepository.findByPlatformAndFavoriteTeamIdAndNotificationsEnabledTrue(eq("ios"), eq("ssg")))
                .thenReturn(List.of(matching));

        mockMvc.perform(post("/admin/test/apns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("ssg")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matchedDeviceCount").value(1))
                .andExpect(jsonPath("$.attemptedCount").value(1))
                .andExpect(jsonPath("$.sentCount").value(0))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.failureReasons[0]").value(ApnsPushService.APNS_INVALID_PROVIDER_TOKEN))
                .andExpect(jsonPath("$.failureReasonCounts.apns_invalid_provider_token").value(1));
    }

    private String requestJson(String favoriteTeamId) {
        return """
                {
                  "favoriteTeamId": "%s",
                  "title": "KBO Score test",
                  "body": "APNs manual test",
                  "deepLink": "kboscore://test"
                }
                """.formatted(favoriteTeamId);
    }

    private NotificationDevice device(String platform, String deviceEnvironment, String favoriteTeamId, boolean notificationsEnabled) {
        return new NotificationDevice(
                UUID.randomUUID(),
                platform,
                deviceEnvironment,
                "token-" + UUID.randomUUID(),
                "install-" + UUID.randomUUID(),
                favoriteTeamId,
                notificationsEnabled,
                OffsetDateTime.now(CLOCK)
        );
    }

    private static final class RecordingApnsPushService extends ApnsPushService {

        private ApnsSendResult result;
        private final String configuredEnvironment;
        private final List<NotificationDevice> sentDevices = new ArrayList<>();

        private RecordingApnsPushService(ApnsSendResult result, String configuredEnvironment) {
            super(new ApnsProperties(), CLOCK);
            this.result = result;
            this.configuredEnvironment = configuredEnvironment;
        }

        @Override
        public ApnsSendResult send(NotificationEvent event, NotificationDevice device) {
            sentDevices.add(device);
            return result;
        }

        @Override
        public String configuredEnvironment() {
            return configuredEnvironment;
        }
    }
}
