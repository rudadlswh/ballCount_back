package com.kbo.crawlerapi.admin;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.admin.AdminDataService.DashboardView;
import com.kbo.crawlerapi.admin.AdminDataService.GameDetailView;
import com.kbo.crawlerapi.admin.AdminDataService.GameHeaderView;
import com.kbo.crawlerapi.admin.AdminDataService.NotificationHistoryView;
import com.kbo.crawlerapi.admin.AdminDataService.NotificationSearchView;
import com.kbo.crawlerapi.admin.AdminDataService.TeamFilterView;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class AdminTemplateRenderingTest {

    private AdminDataService dataService;
    private AdminLogBuffer logBuffer;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dataService = new StubAdminDataService();
        logBuffer = new StubAdminLogBuffer();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminPageController(dataService, logBuffer))
                .setViewResolvers(viewResolver())
                .build();
    }

    @Test
    void dashboardTemplateRendersWithEmptyOperationalData() throws Exception {
        ((StubAdminDataService) dataService).dashboard = new DashboardView(
                "정상", "1시간 2분", "정상", "3 ms", "실행 중", "success", "PT1S",
                null, 12, 1, List.of(), 0, "a****", "••••••••", "d****"
        );

        mockMvc.perform(get("/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("대시보드")))
                .andExpect(content().string(containsString("현재 진행 중인 경기가 없습니다")));
    }

    @Test
    void detailIssuesAndLogsTemplatesRender() throws Exception {
        GameHeaderView game = new GameHeaderView(
                "00000000-0000-0000-0000-000000000001", "20260725-LG-KIA", "provider-1",
                "2026-07-25", "2026-07-25 18:30:00", "잠실", "live", "KIA", "LG",
                1, 2, "7회말", "2026-07-25 20:10:00", "2026-07-25 20:10:01", "2026-07-25 20:10:01"
        );
        ((StubAdminDataService) dataService).detail = Optional.of(
                new GameDetailView(game, List.of(), List.of(), List.of(), List.of())
        );

        mockMvc.perform(get("/admin/games/20260725-LG-KIA"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("KIA vs LG")));
        mockMvc.perform(get("/admin/issues"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("현재 탐지된 오류 의심 건이 없습니다")));
        mockMvc.perform(get("/admin/logs"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("조건에 맞는 로그가 없습니다")));
    }

    @Test
    void notificationHistoryRendersAndAcceptsFilters() throws Exception {
        ((StubAdminDataService) dataService).notificationResult = new NotificationSearchView(
                List.of(new NotificationHistoryView(
                        "20260728-LG-KIA", "2026-07-28", "KIA", "LG", "SCORE_CHANGE",
                        "KIA 득점", "7회초 KIA가 득점했습니다.", "failed", "APNS timeout",
                        "2026-07-28 20:10:00", "2026-07-28 20:10:01", "2026-07-28 20:10:01"
                )),
                1, 0, 1, 0
        );

        mockMvc.perform(get("/admin/notifications")
                        .param("from", "2026-07-28")
                        .param("to", "2026-07-28")
                        .param("team", "LG")
                        .param("status", "failed")
                        .param("gameId", "20260728")
                        .param("eventType", "SCORE"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("알림 발송 내역")))
                .andExpect(content().string(containsString("KIA vs LG")))
                .andExpect(content().string(containsString("APNS timeout")));

        StubAdminDataService stub = (StubAdminDataService) dataService;
        org.assertj.core.api.Assertions.assertThat(stub.requestedFrom).isEqualTo(LocalDate.of(2026, 7, 28));
        org.assertj.core.api.Assertions.assertThat(stub.requestedTeam).isEqualTo("LG");
        org.assertj.core.api.Assertions.assertThat(stub.requestedStatus).isEqualTo("failed");
    }

    private ThymeleafViewResolver viewResolver() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode("HTML");
        templateResolver.setCharacterEncoding("UTF-8");

        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);

        ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
        viewResolver.setTemplateEngine(templateEngine);
        viewResolver.setCharacterEncoding("UTF-8");
        return viewResolver;
    }

    private static final class StubAdminDataService extends AdminDataService {
        private DashboardView dashboard;
        private Optional<GameDetailView> detail = Optional.empty();
        private NotificationSearchView notificationResult = new NotificationSearchView(List.of(), 0, 0, 0, 0);
        private LocalDate requestedFrom;
        private String requestedTeam;
        private String requestedStatus;

        private StubAdminDataService() {
            super(null, null, null, null, null, null, null, null);
        }

        @Override
        public DashboardView dashboard() {
            return dashboard;
        }

        @Override
        public Optional<GameDetailView> gameDetail(String publicGameId) {
            return detail;
        }

        @Override
        public List<AdminDataService.IssueView> issues() {
            return List.of();
        }

        @Override
        public List<TeamFilterView> notificationTeams() {
            return List.of(new TeamFilterView("LG", "LG"), new TeamFilterView("KIA", "KIA"));
        }

        @Override
        public NotificationSearchView notificationHistory(
                LocalDate from,
                LocalDate to,
                String teamCode,
                String deliveryStatus,
                String gameId,
                String eventType
        ) {
            requestedFrom = from;
            requestedTeam = teamCode;
            requestedStatus = deliveryStatus;
            return notificationResult;
        }
    }

    private static final class StubAdminLogBuffer extends AdminLogBuffer {
        private StubAdminLogBuffer() {
            super(new AdminUiProperties());
        }

        @Override
        public List<LogView> search(LocalDateTime from, LocalDateTime to, String level, String gameId, String feature) {
            return List.of();
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
