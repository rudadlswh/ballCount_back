package com.kbo.crawlerapi.admin;

import com.kbo.crawlerapi.service.ApnsTestPushService;
import com.kbo.crawlerapi.service.ApnsTestPushService.ApnsTestPushCommand;
import jakarta.servlet.http.HttpServletResponse;
import com.kbo.crawlerapi.service.GameReadService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminPageController {

    private final AdminDataService dataService;
    private final AdminLogBuffer logBuffer;
    private final GameReadService gameReadService;
    private final ApnsTestPushService apnsTestPushService;

    public AdminPageController(
            AdminDataService dataService,
            AdminLogBuffer logBuffer,
            GameReadService gameReadService,
            ApnsTestPushService apnsTestPushService
    ) {
        this.dataService = dataService;
        this.logBuffer = logBuffer;
        this.gameReadService = gameReadService;
        this.apnsTestPushService = apnsTestPushService;
    }

    @GetMapping("/admin")
    public String index() {
        return "redirect:/admin/dashboard";
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        YearMonth nextMonth = YearMonth.now(ZoneId.of("Asia/Seoul")).plusMonths(1);
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("dashboard", dataService.dashboard());
        model.addAttribute("scheduleImportFromMonth", nextMonth);
        model.addAttribute("scheduleImportToMonth", nextMonth.plusMonths(1));
        return "admin/dashboard";
    }

    @GetMapping("/admin/fragments/dashboard")
    public String dashboardFragment(Model model) {
        model.addAttribute("dashboard", dataService.dashboard());
        return "admin/fragments/dashboard :: content";
    }

    @GetMapping("/admin/games/{publicGameId}")
    public String gameDetail(
            @PathVariable String publicGameId,
            Model model,
            HttpServletResponse response
    ) {
        var detail = dataService.gameDetail(publicGameId);
        if (detail.isEmpty()) {
            response.setStatus(HttpStatus.NOT_FOUND.value());
            model.addAttribute("activePage", "dashboard");
            model.addAttribute("message", "경기를 찾을 수 없습니다: " + publicGameId);
            return "admin/not-found";
        }
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("detail", detail.get());
        return "admin/game-detail";
    }

    @GetMapping("/admin/games")
    public String games(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            Model model
    ) {
        YearMonth currentMonth = YearMonth.now(ZoneId.of("Asia/Seoul"));
        try {
            YearMonth requestedMonth = YearMonth.of(
                    year == null ? currentMonth.getYear() : year,
                    month == null ? currentMonth.getMonthValue() : month
            );
            model.addAttribute("activePage", "games");
            model.addAttribute("requestedMonth", requestedMonth);
            model.addAttribute("monthResult", gameReadService.getGamesByMonth(requestedMonth));
            return "admin/games";
        } catch (java.time.DateTimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be in the range 1-12");
        }
    }

    @GetMapping("/admin/issues")
    public String issues(Model model) {
        model.addAttribute("activePage", "issues");
        model.addAttribute("issues", dataService.issues());
        return "admin/issues";
    }

    @GetMapping("/admin/notifications")
    public String notifications(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String team,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String gameId,
            @RequestParam(required = false) String eventType,
            Model model
    ) {
        addNotifications(model, from, to, team, status, gameId, eventType);
        model.addAttribute("notificationTeams", dataService.notificationTeams());
        model.addAttribute("notificationTeamDeviceCounts", dataService.notificationTeamDeviceCounts());
        model.addAttribute("activePage", "notifications");
        return "admin/notifications";
    }

    @GetMapping("/admin/fragments/notifications")
    public String notificationsFragment(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String team,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String gameId,
            @RequestParam(required = false) String eventType,
            Model model
    ) {
        addNotifications(model, from, to, team, status, gameId, eventType);
        return "admin/fragments/notification-table :: content";
    }

    @PostMapping("/admin/notifications/manual")
    public String sendManualNotification(
            @RequestParam String favoriteTeamId,
            @RequestParam String title,
            @RequestParam String body,
            @RequestParam(required = false) String deepLink,
            RedirectAttributes redirectAttributes
    ) {
        if (!apnsTestPushService.isEnabled()) {
            redirectAttributes.addFlashAttribute(
                    "manualNotificationError",
                    "수동 알림 발송이 비활성화되어 있습니다. KBO_APNS_TEST_ENABLED 설정을 확인해 주세요."
            );
            return "redirect:/admin/notifications";
        }
        try {
            var result = apnsTestPushService.sendTestPush(new ApnsTestPushCommand(
                    favoriteTeamId,
                    requireManualNotificationText(title, "제목", 100),
                    requireManualNotificationText(body, "내용", 500),
                    optionalManualNotificationText(deepLink, "딥링크", 500)
            ));
            redirectAttributes.addFlashAttribute("manualNotificationResult", result);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirectAttributes.addFlashAttribute("manualNotificationError", exception.getMessage());
        }
        return "redirect:/admin/notifications";
    }

    @GetMapping("/admin/logs")
    public String logs(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime to,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String gameId,
            @RequestParam(required = false) String feature,
            Model model
    ) {
        addLogs(model, from, to, level, gameId, feature);
        model.addAttribute("activePage", "logs");
        return "admin/logs";
    }

    @GetMapping("/admin/fragments/logs")
    public String logsFragment(
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd'T'HH:mm") LocalDateTime to,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String gameId,
            @RequestParam(required = false) String feature,
            Model model
    ) {
        addLogs(model, from, to, level, gameId, feature);
        return "admin/fragments/log-table :: content";
    }

    private void addLogs(
            Model model,
            LocalDateTime from,
            LocalDateTime to,
            String level,
            String gameId,
            String feature
    ) {
        model.addAttribute("logs", logBuffer.search(from, to, level, gameId, feature));
        model.addAttribute("logBufferSize", logBuffer.size());
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("level", level == null ? "" : level);
        model.addAttribute("gameId", gameId == null ? "" : gameId);
        model.addAttribute("feature", feature == null ? "" : feature);
    }

    private void addNotifications(
            Model model,
            LocalDate from,
            LocalDate to,
            String team,
            String status,
            String gameId,
            String eventType
    ) {
        if (from == null && to == null) {
            from = LocalDate.now(ZoneId.of("Asia/Seoul"));
            to = from;
        } else if (from != null && to != null && from.isAfter(to)) {
            LocalDate originalFrom = from;
            from = to;
            to = originalFrom;
        }
        String normalizedTeam = team == null ? "" : team.trim();
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase(java.util.Locale.ROOT);
        String normalizedGameId = gameId == null ? "" : gameId.trim();
        String normalizedEventType = eventType == null ? "" : eventType.trim();

        model.addAttribute(
                "notificationResult",
                dataService.notificationHistory(
                        from,
                        to,
                        normalizedTeam,
                        normalizedStatus,
                        normalizedGameId,
                        normalizedEventType
                )
        );
        model.addAttribute("from", from);
        model.addAttribute("to", to);
        model.addAttribute("team", normalizedTeam);
        model.addAttribute("status", normalizedStatus);
        model.addAttribute("gameId", normalizedGameId);
        model.addAttribute("eventType", normalizedEventType);
        model.addAttribute("manualNotificationEnabled", apnsTestPushService.isEnabled());
    }

    private String requireManualNotificationText(String value, String fieldName, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + "을(를) 입력해 주세요.");
        }
        return optionalManualNotificationText(value, fieldName, maxLength);
    }

    private String optionalManualNotificationText(String value, String fieldName, int maxLength) {
        String normalized = value == null ? null : value.trim();
        if (normalized != null && normalized.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + "은(는) " + maxLength + "자 이하여야 합니다.");
        }
        return normalized;
    }
}
