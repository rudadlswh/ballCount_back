package com.kbo.crawlerapi.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
import com.kbo.crawlerapi.service.DayScheduleIngestionResult;
import com.kbo.crawlerapi.service.KboScheduleImportService;
import com.kbo.crawlerapi.service.MonthScheduleIngestionResult;

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

    @Test
    void crawlMonthReturnsRequestedMonthAndAggregatedCounts() throws Exception {
        mockMvc.perform(post("/admin/crawl/month").param("year", "2026").param("month", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2026))
                .andExpect(jsonPath("$.month").value(4))
                .andExpect(jsonPath("$.from").value("2026-04-01"))
                .andExpect(jsonPath("$.to").value("2026-04-30"))
                .andExpect(jsonPath("$.totalDays").value(30))
                .andExpect(jsonPath("$.successDays").value(29))
                .andExpect(jsonPath("$.failedDays").value(1))
                .andExpect(jsonPath("$.created").value(12))
                .andExpect(jsonPath("$.updated").value(135))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.failed").value(1))
                .andExpect(jsonPath("$.failures[0].date").value("2026-04-15"))
                .andExpect(jsonPath("$.failures[0].message").value("KBO source timeout"))
                .andExpect(jsonPath("$.dailyResults[0].date").value("2026-04-01"))
                .andExpect(jsonPath("$.dailyResults[0].created").value(1))
                .andExpect(jsonPath("$.dailyResults[0].updated").value(4))
                .andExpect(jsonPath("$.dailyResults[0].skipped").value(0))
                .andExpect(jsonPath("$.dailyResults[0].failed").value(0))
                .andExpect(jsonPath("$.dailyResults[0].status").value("SUCCESS"));

        assertThat(kboScheduleImportService.requestedYearMonth).isEqualTo(YearMonth.of(2026, 4));
    }

    @Test
    void crawlMonthRejectsInvalidMonth() throws Exception {
        mockMvc.perform(post("/admin/crawl/month").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    private static final class StubKboScheduleImportService extends KboScheduleImportService {

        private LocalDate requestedDate;
        private YearMonth requestedYearMonth;

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

        @Override
        public MonthScheduleIngestionResult crawlMonth(YearMonth yearMonth) {
            this.requestedYearMonth = yearMonth;
            return new MonthScheduleIngestionResult(
                    yearMonth,
                    yearMonth.atDay(1),
                    yearMonth.atEndOfMonth(),
                    yearMonth.lengthOfMonth(),
                    yearMonth.lengthOfMonth() - 1,
                    1,
                    12,
                    135,
                    2,
                    1,
                    List.of(new MonthScheduleIngestionResult.Failure(LocalDate.of(2026, 4, 15), "KBO source timeout")),
                    List.of(new MonthScheduleIngestionResult.DailyResult(
                            LocalDate.of(2026, 4, 1),
                            1,
                            4,
                            0,
                            0,
                            MonthScheduleIngestionResult.DailyStatus.SUCCESS
                    ))
            );
        }
    }
}
