package com.kbo.crawlerapi.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.api.AdminRankController;
import com.kbo.crawlerapi.api.InternalDetailRefreshOrchestrationController;
import com.kbo.crawlerapi.api.PublicPageController;
import com.kbo.crawlerapi.service.DetailRefreshOrchestratorService;
import com.kbo.crawlerapi.service.TeamRankService;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminApiKeyFilterTest {

    private static final String ADMIN_API_KEY = "test-admin-key";

    private StubTeamRankService teamRankService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAdminApiKey(ADMIN_API_KEY);
        teamRankService = new StubTeamRankService();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PublicPageController(),
                        new AdminRankController(teamRankService),
                        new InternalDetailRefreshOrchestrationController(new StubDetailRefreshOrchestratorService())
                )
                .addFilters(new AdminApiKeyFilter(properties))
                .build();
    }

    @Test
    void supportIsPublic() throws Exception {
        mockMvc.perform(get("/support"))
                .andExpect(status().isOk());
    }

    @Test
    void privacyIsPublic() throws Exception {
        mockMvc.perform(get("/privacy"))
                .andExpect(status().isOk());
    }

    @Test
    void adminRequestWithoutApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/ranks/refresh").param("season", "2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRequestWithWrongApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/admin/ranks/refresh")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, "wrong-key")
                        .param("season", "2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRequestReturnsUnauthorizedWhenConfiguredApiKeyIsBlank() throws Exception {
        AppSecurityProperties properties = new AppSecurityProperties();
        properties.setAdminApiKey(" ");
        MockMvc blankKeyMockMvc = MockMvcBuilders.standaloneSetup(new AdminRankController(teamRankService))
                .addFilters(new AdminApiKeyFilter(properties))
                .build();

        blankKeyMockMvc.perform(post("/admin/ranks/refresh")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_API_KEY)
                        .param("season", "2026"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminRequestWithValidApiKeyReachesController() throws Exception {
        mockMvc.perform(post("/admin/ranks/refresh")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_API_KEY)
                        .param("season", "2026"))
                .andExpect(status().isOk());

        assertThat(teamRankService.requestedSeason).isEqualTo(2026);
    }

    @Test
    void internalRequestWithoutApiKeyReturnsUnauthorized() throws Exception {
        mockMvc.perform(post("/internal/orchestration/detail-refresh-pass"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalRequestWithValidApiKeyReachesController() throws Exception {
        mockMvc.perform(post("/internal/orchestration/detail-refresh-pass")
                        .header(AdminApiKeyFilter.ADMIN_API_KEY_HEADER, ADMIN_API_KEY))
                .andExpect(status().isOk());
    }

    private static final class StubTeamRankService extends TeamRankService {

        private Integer requestedSeason;

        private StubTeamRankService() {
            super(null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public TeamRankRefreshResult refreshSeasonRankings(int season) {
            requestedSeason = season;
            return new TeamRankRefreshResult(season, 42, 10);
        }
    }

    private static final class StubDetailRefreshOrchestratorService extends DetailRefreshOrchestratorService {

        private StubDetailRefreshOrchestratorService() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public DetailRefreshPassResult runPass(LocalDate date, boolean execute) {
            return new DetailRefreshPassResult(
                    LocalDate.of(2026, 4, 9),
                    execute,
                    0,
                    0,
                    java.util.List.of(),
                    java.util.List.of()
            );
        }
    }
}
