package com.kbo.crawlerapi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationEventResult;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestCommand;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminNotificationReplayTestControllerWebMvcTest {

    private static final String ADMIN_KEY = "admin-test-key";

    private StubReplayTestService replayTestService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        replayTestService = new StubReplayTestService();
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAdminApiKey(ADMIN_KEY);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminNotificationReplayTestController(replayTestService))
                .addFilters(new AdminApiKeyFilter(properties))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void replayRequiresAdminApiKey() throws Exception {
        mockMvc.perform(post("/admin/test/notifications/replay-finished-game")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("install-1")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void replayRejectsMissingInstallationId() throws Exception {
        replayTestService.exception = new InvalidParameterException("installationId is required");

        mockMvc.perform(post("/admin/test/notifications/replay-finished-game")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson(null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void replayReturnsServiceResult() throws Exception {
        replayTestService.result = new ReplayFinishedGameNotificationTestResult(
                        "8e8f7cc5-3c62-45bb-9b50-6e75a82b5a11",
                        "20260617-SSG-LOT",
                        "install-1",
                        false,
                        1,
                        1,
                        1,
                        0,
                        0,
                        List.of(new ReplayFinishedGameNotificationEventResult(
                                1,
                                "SCORE_CHANGED",
                                "[테스트] 롯데 득점",
                                "1회말 롯데 1득점",
                                "sent",
                                null
                        ))
                );

        mockMvc.perform(post("/admin/test/notifications/replay-finished-game")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("install-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetInstallationId").value("install-1"))
                .andExpect(jsonPath("$.generatedCount").value(1))
                .andExpect(jsonPath("$.events[0].title").value("[테스트] 롯데 득점"));

        org.assertj.core.api.Assertions.assertThat(replayTestService.called).isTrue();
    }

    private String requestJson(String installationId) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("publicGameId", "20260617-SSG-LOT");
        payload.put("installationId", installationId);
        payload.put("eventTypes", List.of("SCORE_CHANGED"));
        payload.put("maxEvents", 20);
        payload.put("dryRun", false);
        return new ObjectMapper().writeValueAsString(payload);
    }

    private static final class StubReplayTestService extends FinishedGameNotificationReplayTestService {

        private ReplayFinishedGameNotificationTestResult result;
        private RuntimeException exception;
        private boolean called;

        private StubReplayTestService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ReplayFinishedGameNotificationTestResult replay(ReplayFinishedGameNotificationTestCommand command) {
            called = true;
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }
}
