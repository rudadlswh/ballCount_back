package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.kbo.crawlerapi.service.DayScheduleIngestionResult;
import com.kbo.crawlerapi.service.KboScheduleImportService;

class AdminCrawlControllerWebMvcTest {

    private StubKboScheduleImportService kboScheduleImportService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kboScheduleImportService = new StubKboScheduleImportService();
        AdminCrawlController controller = new AdminCrawlController(kboScheduleImportService);
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
    void crawlDayReturnsRequestedDateAndProcessedCounts() throws Exception {
        mockMvc.perform(post("/admin/crawl/day").param("date", "2026-04-17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2026-04-17"))
                .andExpect(jsonPath("$.teamCreatedCount").value(2))
                .andExpect(jsonPath("$.teamUpdatedCount").value(0))
                .andExpect(jsonPath("$.gameProcessedCount").value(1))
                .andExpect(jsonPath("$.gameCreatedCount").value(1))
                .andExpect(jsonPath("$.gameUpdatedCount").value(0))
                .andExpect(jsonPath("$.skippedRowCount").value(1))
                .andExpect(jsonPath("$.skippedMissingProviderGameIdCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));

        assertThat(kboScheduleImportService.requestedDate).isEqualTo(LocalDate.of(2026, 4, 17));
    }

    @Test
    void crawlDayRejectsInvalidDate() throws Exception {
        mockMvc.perform(post("/admin/crawl/day").param("date", "2026-04-31"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    @Test
    void crawlDayRequiresDate() throws Exception {
        mockMvc.perform(post("/admin/crawl/day"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private static final class StubKboScheduleImportService extends KboScheduleImportService {

        private LocalDate requestedDate;

        private StubKboScheduleImportService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public DayScheduleIngestionResult crawlDay(LocalDate date) {
            this.requestedDate = date;
            return new DayScheduleIngestionResult(
                    date,
                    2,
                    0,
                    1,
                    1,
                    0,
                    1,
                    1,
                    0,
                    List.of()
            );
        }
    }
}
