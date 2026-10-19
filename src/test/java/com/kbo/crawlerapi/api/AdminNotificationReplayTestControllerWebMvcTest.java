package com.kbo.crawlerapi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.config.AdminApiKeyFilter;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationEventResult;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestCommand;
import com.kbo.crawlerapi.service.FinishedGameNotificationReplayTestService.ReplayFinishedGameNotificationTestResult;
import com.kbo.crawlerapi.service.LiveGameSyncService;
import com.kbo.crawlerapi.service.LiveGameSyncService.NotificationRecoveryDiagnosis;
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
    private StubLiveGameSyncService liveGameSyncService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        replayTestService = new StubReplayTestService();
        liveGameSyncService = new StubLiveGameSyncService();
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAdminApiKey(ADMIN_KEY);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AdminNotificationReplayTestController(replayTestService, liveGameSyncService))
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
                        "sandbox",
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
                        .content(requestJson("install-1", "sandbox")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetInstallationId").value("install-1"))
                .andExpect(jsonPath("$.targetEnvironment").value("sandbox"))
                .andExpect(jsonPath("$.generatedCount").value(1))
                .andExpect(jsonPath("$.events[0].title").value("[테스트] 롯데 득점"));

        org.assertj.core.api.Assertions.assertThat(replayTestService.called).isTrue();
        org.assertj.core.api.Assertions.assertThat(replayTestService.command.environment()).isEqualTo("sandbox");
    }

    @Test
    void replayAllowsOmittedEnvironment() throws Exception {
        replayTestService.result = new ReplayFinishedGameNotificationTestResult(
                "8e8f7cc5-3c62-45bb-9b50-6e75a82b5a11",
                "20260617-SSG-LOT",
                "install-1",
                "production",
                true,
                1,
                0,
                0,
                0,
                0,
                List.of(new ReplayFinishedGameNotificationEventResult(
                        1,
                        "SCORE_CHANGED",
                        "[테스트] 롯데 득점",
                        "1회말 롯데 1득점",
                        "dry_run",
                        null
                ))
        );

        mockMvc.perform(post("/admin/test/notifications/replay-finished-game")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson("install-1")))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(replayTestService.command.environment()).isNull();
    }

    @Test
    void diagnoseGameReturnsSnapshotRecoveryCounts() throws Exception {
        liveGameSyncService.diagnosis = new NotificationRecoveryDiagnosis(
                "8e8f7cc5-3c62-45bb-9b50-6e75a82b5a11",
                "20260617-SSG-LOT",
                4,
                3,
                2,
                1
        );

        mockMvc.perform(get("/admin/test/notifications/diagnose-game")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_KEY)
                        .param("publicGameId", "20260617-SSG-LOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotCount").value(4))
                .andExpect(jsonPath("$.candidateEventCount").value(3))
                .andExpect(jsonPath("$.storedEventCount").value(2))
                .andExpect(jsonPath("$.suspectedMissingEventCount").value(1));

        org.assertj.core.api.Assertions.assertThat(liveGameSyncService.publicGameId).isEqualTo("20260617-SSG-LOT");
    }

    private String requestJson(String installationId) throws Exception {
        return requestJson(installationId, null);
    }

    private String requestJson(String installationId, String environment) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("publicGameId", "20260617-SSG-LOT");
        payload.put("installationId", installationId);
        if (environment != null) {
            payload.put("environment", environment);
        }
        payload.put("eventTypes", List.of("SCORE_CHANGED"));
        payload.put("maxEvents", 20);
        payload.put("dryRun", false);
        return new ObjectMapper().writeValueAsString(payload);
    }

    private static final class StubReplayTestService extends FinishedGameNotificationReplayTestService {

        private ReplayFinishedGameNotificationTestResult result;
        private RuntimeException exception;
        private boolean called;
        private ReplayFinishedGameNotificationTestCommand command;

        private StubReplayTestService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public ReplayFinishedGameNotificationTestResult replay(ReplayFinishedGameNotificationTestCommand command) {
            called = true;
            this.command = command;
            if (exception != null) {
                throw exception;
            }
            return result;
        }
    }

    private static final class StubLiveGameSyncService extends LiveGameSyncService {

        private NotificationRecoveryDiagnosis diagnosis;
        private String publicGameId;

        private StubLiveGameSyncService() {
            super(null, null, null, null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public NotificationRecoveryDiagnosis diagnoseNotificationRecovery(String publicGameId) {
            this.publicGameId = publicGameId;
            return diagnosis;
        }
    }
}
