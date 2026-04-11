package com.kbo.crawlerapi.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.util.List;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.kbo.crawlerapi.api.dto.GameDetailResponse;
import com.kbo.crawlerapi.api.dto.GameLineScoreResponse;
import com.kbo.crawlerapi.api.dto.GameTotalsDto;
import com.kbo.crawlerapi.api.dto.GameStateDto;
import com.kbo.crawlerapi.api.dto.GameSummaryDto;
import com.kbo.crawlerapi.api.dto.GamesByDateResponse;
import com.kbo.crawlerapi.api.dto.GamesByMonthResponse;
import com.kbo.crawlerapi.api.dto.LineScoreInningDto;
import com.kbo.crawlerapi.api.dto.ScoreboardGameDto;
import com.kbo.crawlerapi.api.dto.ScoreboardResponse;
import com.kbo.crawlerapi.api.dto.TeamSummaryDto;
import com.kbo.crawlerapi.service.GameReadService;

class GameReadControllerWebMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        GameReadController controller = new GameReadController(new StubGameReadService());
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .modules(new JavaTimeModule())
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()
                ))
                .build();
    }

    @Test
    void getGamesByDateReturnsNormalizedSchedulePayload() throws Exception {
        mockMvc.perform(get("/api/v1/games").param("date", "2026-04-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-04-09"))
                .andExpect(jsonPath("$.games.length()").value(1))
                .andExpect(jsonPath("$.games[0].id").value("20260409-LG-SSG"))
                .andExpect(jsonPath("$.games[0].awayScore").isEmpty())
                .andExpect(jsonPath("$.games[0].homeScore").isEmpty())
                .andExpect(jsonPath("$.games[0].cancelReason").isEmpty())
                .andExpect(jsonPath("$.games[0].sourceUpdatedAt").value("2026-04-09T17:58:14+09:00"))
                .andExpect(jsonPath("$.isStale").value(false));
    }

    @Test
    void getGamesByMonthReturnsPayload() throws Exception {
        mockMvc.perform(get("/api/v1/games/month").param("year", "2026").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(4))
                .andExpect(jsonPath("$.games.length()").value(0));
    }

    @Test
    void getScoreboardReturnsPayload() throws Exception {
        mockMvc.perform(get("/api/v1/scoreboard").param("date", "2026-04-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-04-10"))
                .andExpect(jsonPath("$.games.length()").value(1))
                .andExpect(jsonPath("$.games[0].id").value("20260410-KIA-DOO"))
                .andExpect(jsonPath("$.games[0].cancelReason").isEmpty())
                .andExpect(jsonPath("$.games[0].state").isEmpty())
                .andExpect(jsonPath("$.isStale").value(false));
    }

    @Test
    void getGameDetailReturnsSnapshotBackedPayload() throws Exception {
        mockMvc.perform(get("/api/v1/games/20260409-LG-SSG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("20260409-LG-SSG"))
                .andExpect(jsonPath("$.status").value("live"))
                .andExpect(jsonPath("$.awayScore").value(3))
                .andExpect(jsonPath("$.homeScore").value(4))
                .andExpect(jsonPath("$.cancelReason").isEmpty())
                .andExpect(jsonPath("$.state.inning").value(8))
                .andExpect(jsonPath("$.state.half").value("top"))
                .andExpect(jsonPath("$.state.bases.first").value(true))
                .andExpect(jsonPath("$.winningPitcher").isEmpty())
                .andExpect(jsonPath("$.isStale").value(false));
    }

    @Test
    void getGameLineScoreReturnsNormalizedPayload() throws Exception {
        mockMvc.perform(get("/api/v1/games/20260401-LG-KIA/linescore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gameId").value("20260401-LG-KIA"))
                .andExpect(jsonPath("$.innings.length()").value(2))
                .andExpect(jsonPath("$.innings[0].inning").value(1))
                .andExpect(jsonPath("$.innings[0].homeRuns").value(3))
                .andExpect(jsonPath("$.totals.away.runs").value(2))
                .andExpect(jsonPath("$.totals.home.hits").value(8))
                .andExpect(jsonPath("$.isStale").value(false));
    }

    @Test
    void getGamesByMonthRejectsInvalidMonth() throws Exception {
        mockMvc.perform(get("/api/v1/games/month").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private static final class StubGameReadService extends GameReadService {

        private StubGameReadService() {
            super(null, null, null, null);
        }

        @Override
        public GamesByDateResponse getGamesByDate(LocalDate date) {
            return new GamesByDateResponse(
                    date,
                    List.of(new GameSummaryDto(
                            "20260409-LG-SSG",
                            "kbo",
                            "20260409LGSS0",
                            LocalDate.of(2026, 4, 9),
                            OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                            "Jamsil",
                            "scheduled",
                            false,
                            false,
                            null,
                            new TeamSummaryDto("ssg", "SSG Landers", "SSG", "https://example.com/logos/ssg.png"),
                            new TeamSummaryDto("lg", "LG Twins", "LG", "https://example.com/logos/lg.png"),
                            null,
                            null,
                            OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                            OffsetDateTime.of(2026, 4, 9, 17, 58, 14, 0, ZoneOffset.ofHours(9)),
                            false
                    )),
                    OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                    false
            );
        }

        @Override
        public GamesByMonthResponse getGamesByMonth(YearMonth yearMonth) {
            return new GamesByMonthResponse(yearMonth.getYear(), yearMonth.getMonthValue(), List.of(), null, false);
        }

        @Override
        public ScoreboardResponse getScoreboard(LocalDate date) {
            return new ScoreboardResponse(
                    date,
                    List.of(new ScoreboardGameDto(
                            "20260410-KIA-DOO",
                            "live",
                            null,
                            OffsetDateTime.of(2026, 4, 10, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                            "Gwangju",
                            new TeamSummaryDto("kia", "KIA Tigers", "KIA", "https://example.com/logos/kia.png"),
                            new TeamSummaryDto("doosan", "Doosan Bears", "Doosan", "https://example.com/logos/doosan.png"),
                            3,
                            4,
                            null
                    )),
                    OffsetDateTime.of(2026, 4, 10, 20, 12, 0, 0, ZoneOffset.ofHours(9)),
                    false
            );
        }

        @Override
        public GameDetailResponse getGameDetail(String gameId) {
            return new GameDetailResponse(
                    gameId,
                    "kbo",
                    "20260409SKLG0",
                    LocalDate.of(2026, 4, 9),
                    OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                    "Jamsil",
                    "live",
                    false,
                    false,
                    null,
                    new TeamSummaryDto("ssg", "SSG Landers", "SSG", "https://example.com/logos/ssg.png"),
                    new TeamSummaryDto("lg", "LG Twins", "LG", "https://example.com/logos/lg.png"),
                    3,
                    4,
                    new GameStateDto(
                            8,
                            "top",
                            "Top 8",
                            2,
                            1,
                            1,
                            new GameStateDto.BasesDto(true, false, true)
                    ),
                    null,
                    null,
                    null,
                    OffsetDateTime.of(2026, 4, 9, 20, 12, 0, 0, ZoneOffset.ofHours(9)),
                    null,
                    false
            );
        }

        @Override
        public GameLineScoreResponse getGameLineScore(String gameId) {
            return new GameLineScoreResponse(
                    gameId,
                    List.of(
                            new LineScoreInningDto(1, 0, 3),
                            new LineScoreInningDto(5, 1, 0)
                    ),
                    new GameTotalsDto(
                            new GameTotalsDto.TeamTotalsDto(2, 7, 1, 3),
                            new GameTotalsDto.TeamTotalsDto(7, 8, 0, 10)
                    ),
                    OffsetDateTime.of(2026, 4, 9, 20, 12, 0, 0, ZoneOffset.ofHours(9)),
                    false
            );
        }
    }
}
