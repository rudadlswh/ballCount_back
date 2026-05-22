package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kbo.crawlerapi.service.LiveGameSyncService.LiveSyncSummary;
import com.kbo.crawlerapi.service.StaleGameReconciliationService;
import com.kbo.crawlerapi.service.StaleGameReconciliationService.StaleGameReconciliationResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StaleGameReconciliationControllerWebMvcTest {

    private RecordingStaleGameReconciliationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new RecordingStaleGameReconciliationService();
        mockMvc = MockMvcBuilders.standaloneSetup(new StaleGameReconciliationController(service))
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
    void reconcileStaleGamesPassesRequestedDatesToService() throws Exception {
        mockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16","2026-04-15"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-04-15"))
                .andExpect(jsonPath("$.dates[1]").value("2026-04-16"))
                .andExpect(jsonPath("$.processedDateCount").value(2))
                .andExpect(jsonPath("$.failedCount").value(0));

        assertThat(service.requestedDates).containsExactly(
                LocalDate.of(2026, 4, 16),
                LocalDate.of(2026, 4, 15)
        );
    }

    @Test
    void reconcileStaleGamesAlsoSupportsSuggestedUnversionedPath() throws Exception {
        mockMvc.perform(post("/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":["2026-04-16"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dates[0]").value("2026-04-16"));
    }

    @Test
    void reconcileStaleGamesRejectsEmptyDates() throws Exception {
        mockMvc.perform(post("/api/v1/games/reconcile-stale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"dates":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private static final class RecordingStaleGameReconciliationService extends StaleGameReconciliationService {
        private List<LocalDate> requestedDates = List.of();

        private RecordingStaleGameReconciliationService() {
            super(null);
        }

        @Override
        public StaleGameReconciliationResult reconcile(List<LocalDate> dates) {
            requestedDates = new ArrayList<>(dates);
            List<LocalDate> uniqueDates = dates.stream().distinct().sorted().toList();
            return new StaleGameReconciliationResult(
                    uniqueDates,
                    uniqueDates.size(),
                    0,
                    uniqueDates.stream()
                            .map(date -> new LiveSyncSummary(date, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of(), List.of()))
                            .toList()
            );
        }
    }
}
