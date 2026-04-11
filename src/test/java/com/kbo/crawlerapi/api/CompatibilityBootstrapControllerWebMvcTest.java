package com.kbo.crawlerapi.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapGameDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapNotificationDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapResponse;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapRunnerStateDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapTeamDto;
import com.kbo.crawlerapi.service.CompatibilityBootstrapService;

class CompatibilityBootstrapControllerWebMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CompatibilityBootstrapController controller = new CompatibilityBootstrapController(new StubCompatibilityBootstrapService());
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
    void getBootstrapReturnsIosCompatibleSnakeCasePayload() throws Exception {
        UUID gameId = UUID.fromString("0aedcdb0-e727-42c9-a7e5-e3c3bad99392");

        mockMvc.perform(get("/v1/bootstrap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teams.length()").value(1))
                .andExpect(jsonPath("$.teams[0].short_name").value("LG"))
                .andExpect(jsonPath("$.teams[0].english_name").value("LG Twins"))
                .andExpect(jsonPath("$.games.length()").value(1))
                .andExpect(jsonPath("$.games[0].id").value(gameId.toString()))
                .andExpect(jsonPath("$.games[0].provider_game_id").value("20260410LGSS0"))
                .andExpect(jsonPath("$.games[0].scheduled_start").value("2026-04-10T18:30:00+09:00"))
                .andExpect(jsonPath("$.games[0].away_team_id").value("ssg"))
                .andExpect(jsonPath("$.games[0].home_team_id").value("lg"))
                .andExpect(jsonPath("$.games[0].status_code").value("LIVE"))
                .andExpect(jsonPath("$.games[0].season_classification").value("regular_season"))
                .andExpect(jsonPath("$.games[0].bases.first").value(true))
                .andExpect(jsonPath("$.notifications.length()").value(0))
                .andExpect(jsonPath("$.settings").doesNotExist());
    }

    private static final class StubCompatibilityBootstrapService extends CompatibilityBootstrapService {

        private StubCompatibilityBootstrapService() {
            super(null, null, null, null);
        }

        @Override
        public CompatibilityBootstrapResponse getBootstrap() {
            return new CompatibilityBootstrapResponse(
                    List.of(new CompatibilityBootstrapTeamDto(
                            "lg",
                            "LG 트윈스",
                            "LG",
                            "LG Twins",
                            "LG",
                            null
                    )),
                    List.of(new CompatibilityBootstrapGameDto(
                            UUID.fromString("0aedcdb0-e727-42c9-a7e5-e3c3bad99392"),
                            "20260410LGSS0",
                            OffsetDateTime.of(2026, 4, 10, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                            "잠실",
                            "ssg",
                            "lg",
                            3,
                            4,
                            "LIVE",
                            "LIVE",
                            "regular_season",
                            "8회초",
                            new CompatibilityBootstrapRunnerStateDto(true, false, true),
                            1,
                            null,
                            List.of(),
                            null
                    )),
                    List.<CompatibilityBootstrapNotificationDto>of(),
                    null
            );
        }
    }
}
