package com.kbo.crawlerapi.admin;

import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AdminPageController {

    private final AdminDataService dataService;
    private final AdminLogBuffer logBuffer;

    public AdminPageController(AdminDataService dataService, AdminLogBuffer logBuffer) {
        this.dataService = dataService;
        this.logBuffer = logBuffer;
    }

    @GetMapping("/admin")
    public String index() {
        return "redirect:/admin/dashboard";
    }

    @GetMapping("/admin/dashboard")
    public String dashboard(Model model) {
        model.addAttribute("activePage", "dashboard");
        model.addAttribute("dashboard", dataService.dashboard());
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
    }
}
