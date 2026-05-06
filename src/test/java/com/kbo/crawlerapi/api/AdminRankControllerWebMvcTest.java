package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.service.TeamRankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminRankControllerWebMvcTest {

    private StubTeamRankService teamRankService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        teamRankService = new StubTeamRankService();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminRankController(teamRankService))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void refreshTriggersTeamRankService() throws Exception {
        mockMvc.perform(post("/admin/ranks/refresh").param("season", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.season").value(2026))
                .andExpect(jsonPath("$.completedGameCount").value(42))
                .andExpect(jsonPath("$.rowCount").value(10));

        assertThat(teamRankService.requestedSeason).isEqualTo(2026);
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
}
