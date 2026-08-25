package com.kbo.crawlerapi.admin;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kbo.crawlerapi.admin.AdminDataService.DashboardView;
import com.kbo.crawlerapi.admin.AdminDataService.GameDetailView;
import com.kbo.crawlerapi.admin.AdminDataService.GameHeaderView;
import com.kbo.crawlerapi.admin.AdminDataService.NotificationHistoryView;
import com.kbo.crawlerapi.admin.AdminDataService.NotificationSearchView;
import com.kbo.crawlerapi.admin.AdminDataService.TeamFilterView;
import com.kbo.crawlerapi.admin.AdminDataService.TeamDeviceCountView;
import com.kbo.crawlerapi.api.dto.GameSummaryDto;
import com.kbo.crawlerapi.api.dto.GamesByMonthResponse;
import com.kbo.crawlerapi.api.dto.TeamSummaryDto;
import com.kbo.crawlerapi.config.AppSecurityProperties;
import com.kbo.crawlerapi.config.ApnsProperties;
import com.kbo.crawlerapi.config.RegistrationRequestSizeLimitFilter;
import com.kbo.crawlerapi.service.ApnsTestPushService;
import com.kbo.crawlerapi.service.ApnsTestPushService.ApnsTestPushCommand;
import com.kbo.crawlerapi.service.ApnsTestPushService.ApnsTestPushResult;
import com.kbo.crawlerapi.service.GameReadService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class AdminTemplateRenderingTest {

    private AdminDataService dataService;
    private AdminLogBuffer logBuffer;
    private StubGameReadService gameReadService;
    private StubApnsTestPushService apnsTestPushService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        dataService = new StubAdminDataService();
        logBuffer = new StubAdminLogBuffer();
        gameReadService = new StubGameReadService();
        apnsTestPushService = new StubApnsTestPushService();
        AppSecurityProperties securityProperties = new AppSecurityProperties();
        securityProperties.setRegistrationRequestMaxBytes(32 * 1024);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new AdminPageController(dataService, logBuffer, gameReadService, apnsTestPushService)
                )
                .addFilters(new RegistrationRequestSizeLimitFilter(securityProperties))
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
                .andExpect(content().string(containsString("월간 일정 수동 수집")))
                .andExpect(content().string(containsString("현재 진행 중인 경기가 없습니다")));
    }

    @Test
    void dashboardTemplateRendersScheduleImportResult() throws Exception {
        ((StubAdminDataService) dataService).dashboard = new DashboardView(
                "정상", "1시간 2분", "정상", "3 ms", "실행 중", "success", "PT1S",
                null, 12, 1, List.of(), 0, "a****", "••••••••", "d****"
        );
        var september = new AdminScheduleImportService.ScheduleImportMonthResult(
                YearMonth.of(2026, 9), true, 76, 30, 0, null
        );
        var october = new AdminScheduleImportService.ScheduleImportMonthResult(
                YearMonth.of(2026, 10), true, 29, 0, 0, null
        );
        var result = new AdminScheduleImportService.ScheduleImportRangeResult(
                YearMonth.of(2026, 9), YearMonth.of(2026, 10), 2, 2, 0, 105, 30, 0,
                List.of(september, october)
        );

        mockMvc.perform(get("/admin/dashboard").flashAttr("scheduleImportResult", result))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2/2개월 수집 완료")))
                .andExpect(content().string(containsString("2026-09")))
                .andExpect(content().string(containsString("2026-10")));
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
                .andExpect(content().string(containsString("응원 팀별 기기 수")))
                .andExpect(content().string(containsString("12")))
                .andExpect(content().string(containsString("iOS 수동 알림")))
                .andExpect(content().string(containsString("KIA vs LG")))
                .andExpect(content().string(containsString("APNS timeout")));

        StubAdminDataService stub = (StubAdminDataService) dataService;
        org.assertj.core.api.Assertions.assertThat(stub.requestedFrom).isEqualTo(LocalDate.of(2026, 7, 28));
        org.assertj.core.api.Assertions.assertThat(stub.requestedTeam).isEqualTo("LG");
        org.assertj.core.api.Assertions.assertThat(stub.requestedStatus).isEqualTo("failed");
    }

    @Test
    void manualNotificationSendsAndRedirectsToHistory() throws Exception {
        mockMvc.perform(post("/admin/notifications/manual")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("favoriteTeamId=ssg&title=game+start&body=starting+soon"
                                + "&deepLink=kboscore%3A%2F%2Fgame%2F20260815-SSG-LG"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/notifications"))
                .andExpect(flash().attributeExists("manualNotificationResult"));

        org.assertj.core.api.Assertions.assertThat(apnsTestPushService.command.favoriteTeamId()).isEqualTo("ssg");
        org.assertj.core.api.Assertions.assertThat(apnsTestPushService.command.title()).isEqualTo("game start");
    }

    @Test
    void manualNotificationRejectsBlankContent() throws Exception {
        mockMvc.perform(post("/admin/notifications/manual")
                        .param("favoriteTeamId", "ssg")
                        .param("title", "경기 시작")
                        .param("body", " "))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("manualNotificationError"));
    }

    @Test
    void monthlyGamesRendersApiResult() throws Exception {
        TeamSummaryDto away = new TeamSummaryDto("away-id", "KIA 타이거즈", "KIA", null);
        TeamSummaryDto home = new TeamSummaryDto("home-id", "LG 트윈스", "LG", null);
        GameSummaryDto game = new GameSummaryDto(
                "20260728-KIA-LG", "KBO", "provider-1", LocalDate.of(2026, 7, 28),
                OffsetDateTime.parse("2026-07-28T18:30:00+09:00"), "잠실", "final", false, false,
                null, away, home, 2, 3, null, null, OffsetDateTime.parse("2026-07-28T22:00:00+09:00"),
                OffsetDateTime.parse("2026-07-28T22:00:00+09:00"), false
        );
        gameReadService.monthResult = new GamesByMonthResponse(2026, 7, List.of(game), game.updatedAt(), false);

        mockMvc.perform(get("/admin/games").param("year", "2026").param("month", "7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("GET /API/V1/GAMES/MONTH")))
                .andExpect(content().string(containsString("KIA vs LG")))
                .andExpect(content().string(containsString("20260728-KIA-LG")));
        org.assertj.core.api.Assertions.assertThat(gameReadService.requestedMonth).isEqualTo(YearMonth.of(2026, 7));
    }

    @Test
    void monthlyGamesRejectsInvalidMonth() throws Exception {
        mockMvc.perform(get("/admin/games").param("year", "2026").param("month", "13"))
                .andExpect(status().isBadRequest());
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
        public List<TeamDeviceCountView> notificationTeamDeviceCounts() {
            return List.of(
                    new TeamDeviceCountView("LG", "LG", 12, 10, 8, 4),
                    new TeamDeviceCountView("KIA", "KIA", 7, 6, 5, 2)
            );
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

    private static final class StubGameReadService extends GameReadService {
        private GamesByMonthResponse monthResult = new GamesByMonthResponse(2026, 1, List.of(), null, false);
        private YearMonth requestedMonth;

        private StubGameReadService() {
            super(null, null, null, null, java.time.Clock.systemUTC());
        }

        @Override
        public GamesByMonthResponse getGamesByMonth(YearMonth yearMonth) {
            requestedMonth = yearMonth;
            return monthResult;
        }
    }

    private static final class StubApnsTestPushService extends ApnsTestPushService {
        private ApnsTestPushCommand command;

        private StubApnsTestPushService() {
            super(
                    null,
                    null,
                    new ApnsProperties(),
                    new com.fasterxml.jackson.databind.ObjectMapper(),
                    new org.springframework.mock.env.MockEnvironment(),
                    java.time.Clock.systemUTC()
            );
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public ApnsTestPushResult sendTestPush(ApnsTestPushCommand command) {
            this.command = command;
            return new ApnsTestPushResult(2, 2, 2, 0, List.of(), Map.of());
        }
    }
}
