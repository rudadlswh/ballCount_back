package com.kbo.crawlerapi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminScheduleImportControllerTest {

    private StubAdminScheduleImportService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = new StubAdminScheduleImportService();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminScheduleImportController(service)).build();
    }

    @Test
    void importsRequestedMonthRangeAndRedirectsToDashboard() throws Exception {
        mockMvc.perform(post("/admin/schedule/import")
                        .param("fromMonth", "2026-09")
                        .param("toMonth", "2026-10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"))
                .andExpect(flash().attributeExists("scheduleImportResult"));

        assertThat(service.fromMonth).isEqualTo(YearMonth.of(2026, 9));
        assertThat(service.toMonth).isEqualTo(YearMonth.of(2026, 10));
    }

    @Test
    void redirectsWithErrorForInvalidMonth() throws Exception {
        mockMvc.perform(post("/admin/schedule/import")
                        .param("fromMonth", "2026-13")
                        .param("toMonth", "2026-10"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/dashboard"))
                .andExpect(flash().attributeExists("scheduleImportError"));
    }

    private static final class StubAdminScheduleImportService extends AdminScheduleImportService {

        private YearMonth fromMonth;
        private YearMonth toMonth;

        private StubAdminScheduleImportService() {
            super(null);
        }

        @Override
        public ScheduleImportRangeResult importRange(YearMonth fromMonth, YearMonth toMonth) {
            this.fromMonth = fromMonth;
            this.toMonth = toMonth;
            return new ScheduleImportRangeResult(fromMonth, toMonth, 2, 2, 0, 105, 30, 0, List.of());
        }
    }
}
