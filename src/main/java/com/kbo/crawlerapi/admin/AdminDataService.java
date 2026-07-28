package com.kbo.crawlerapi.admin;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.config.SyncProperties;
import com.kbo.crawlerapi.scheduler.LiveGameSyncScheduler;
import java.lang.management.ManagementFactory;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminDataService {

    private static final Logger log = LoggerFactory.getLogger(AdminDataService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final java.time.format.DateTimeFormatter DATE_TIME_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final SyncProperties syncProperties;
    private final LiveSyncProperties liveSyncProperties;
    private final LiveGameSyncScheduler scheduler;
    private final AdminUiProperties adminUiProperties;
    private final AdminAuthenticationService authenticationService;
    private final Environment environment;
    private final Clock clock;

    public AdminDataService(
            JdbcTemplate jdbcTemplate,
            SyncProperties syncProperties,
            LiveSyncProperties liveSyncProperties,
            LiveGameSyncScheduler scheduler,
            AdminUiProperties adminUiProperties,
            AdminAuthenticationService authenticationService,
            Environment environment,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.syncProperties = syncProperties;
        this.liveSyncProperties = liveSyncProperties;
        this.scheduler = scheduler;
        this.adminUiProperties = adminUiProperties;
        this.authenticationService = authenticationService;
        this.environment = environment;
        this.clock = clock;
    }

    public DashboardView dashboard() {
        long startedNanos = System.nanoTime();
        boolean databaseHealthy;
        try {
            Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            databaseHealthy = result != null && result == 1;
        } catch (DataAccessException exception) {
            databaseHealthy = false;
            log.warn("[AdminUI] database health query failed type={}", exception.getClass().getSimpleName());
        }
        long databaseLatencyMillis = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();

        List<GameSummaryView> liveGames = databaseHealthy ? loadLiveGames() : List.of();
        NotificationCounts notificationCounts = databaseHealthy ? loadTodayNotificationCounts() : new NotificationCounts(0, 0);
        JobSummary lastJob = databaseHealthy ? loadLatestJob().orElse(null) : null;
        int issueCount = databaseHealthy ? issues().size() : 0;

        boolean schedulerEnabled = syncProperties.isEnabled();
        String schedulerState = !schedulerEnabled ? "비활성"
                : scheduler.isRunning() ? "실행 중" : "중지됨";
        String schedulerTone = !schedulerEnabled ? "muted" : scheduler.isRunning() ? "success" : "danger";

        return new DashboardView(
                "정상",
                formatUptime(ManagementFactory.getRuntimeMXBean().getUptime()),
                databaseHealthy ? "정상" : "연결 실패",
                databaseHealthy ? databaseLatencyMillis + " ms" : "-",
                schedulerState,
                schedulerTone,
                String.valueOf(liveSyncProperties.getLivePollingInterval()),
                lastJob,
                notificationCounts.sent(),
                notificationCounts.failed(),
                liveGames,
                issueCount,
                authenticationService.maskedUsername(),
                authenticationService.maskedPassword(),
                mask(environment.getProperty("spring.datasource.username"))
        );
    }

    public Optional<GameDetailView> gameDetail(String publicGameId) {
        List<GameHeaderView> games = jdbcTemplate.query("""
                SELECT g.id, g.public_game_id, g.provider_game_id, g.game_date, g.scheduled_at,
                       g.stadium, g.status, g.home_score, g.away_score, g.inning_state,
                       g.source_updated_at, g.live_last_checked_at, g.updated_at,
                       home.short_name AS home_team, away.short_name AS away_team
                FROM games g
                JOIN teams home ON home.id = g.home_team_id
                JOIN teams away ON away.id = g.away_team_id
                WHERE g.public_game_id = ?
                """, (rs, rowNum) -> mapGameHeader(rs), publicGameId);
        if (games.isEmpty()) {
            return Optional.empty();
        }

        GameHeaderView game = games.get(0);
        UUID gameId = UUID.fromString(game.id());
        List<SnapshotView> snapshots = jdbcTemplate.query("""
                SELECT inning, inning_half, inning_label, balls, strikes, outs,
                       runner_on_first, runner_on_second, runner_on_third,
                       first_base_runner_name, second_base_runner_name, third_base_runner_name,
                       current_pitcher_name, current_batter_name, home_score, away_score,
                       source_updated_at, fetched_at, created_at
                FROM game_snapshots
                WHERE game_id = ?
                ORDER BY COALESCE(source_updated_at, fetched_at, created_at) DESC
                LIMIT 50
                """, (rs, rowNum) -> mapSnapshot(rs), gameId);
        List<NotificationView> notifications = jdbcTemplate.query("""
                SELECT event_type, title, body, delivery_status, error_message, created_at, sent_at
                FROM notification_events
                WHERE game_id = ?
                ORDER BY created_at DESC
                LIMIT 100
                """, (rs, rowNum) -> mapNotification(rs), gameId);
        List<CrawlHistoryView> crawlHistory = jdbcTemplate.query("""
                SELECT job_type, status, started_at, finished_at, last_error_message
                FROM crawl_jobs
                WHERE target_key IN (?, ?)
                ORDER BY created_at DESC
                LIMIT 30
                """, (rs, rowNum) -> new CrawlHistoryView(
                        text(rs, "job_type"),
                        text(rs, "status"),
                        format(rs.getObject("started_at", OffsetDateTime.class)),
                        format(rs.getObject("finished_at", OffsetDateTime.class)),
                        text(rs, "last_error_message")
                ), game.publicGameId(), game.providerGameId());
        List<IssueView> gameIssues = issues().stream()
                .filter(issue -> publicGameId.equals(issue.gameId()))
                .toList();

        return Optional.of(new GameDetailView(game, snapshots, notifications, crawlHistory, gameIssues));
    }

    public List<IssueView> issues() {
        List<IssueView> issues = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), KST);
        Duration threshold = adminUiProperties.getStaleGameThreshold() == null
                ? Duration.ofMinutes(2)
                : adminUiProperties.getStaleGameThreshold();
        OffsetDateTime staleBefore = now.minus(threshold);

        issues.addAll(jdbcTemplate.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (game_id) game_id, source_updated_at, fetched_at, created_at
                    FROM game_snapshots
                    ORDER BY game_id, COALESCE(source_updated_at, fetched_at, created_at) DESC
                )
                SELECT g.public_game_id,
                       COALESCE(l.source_updated_at, l.fetched_at, l.created_at, g.live_last_checked_at, g.updated_at) AS detected_at
                FROM games g
                LEFT JOIN latest l ON l.game_id = g.id
                WHERE g.status = 'live'
                  AND (COALESCE(l.source_updated_at, l.fetched_at, l.created_at, g.live_last_checked_at, g.updated_at) IS NULL
                       OR COALESCE(l.source_updated_at, l.fetched_at, l.created_at, g.live_last_checked_at, g.updated_at) < ?)
                ORDER BY detected_at ASC NULLS FIRST
                """, (rs, rowNum) -> new IssueView(
                        "갱신 중단",
                        "danger",
                        text(rs, "public_game_id"),
                        "LIVE 경기 데이터가 임계 시간 동안 갱신되지 않았습니다.",
                        "마지막 갱신 " + format(rs.getObject("detected_at", OffsetDateTime.class))
                                + " · 기준 " + threshold.toSeconds() + "초",
                        format(rs.getObject("detected_at", OffsetDateTime.class))
                ), staleBefore));

        issues.addAll(jdbcTemplate.query("""
                WITH latest AS (
                    SELECT DISTINCT ON (game_id) *
                    FROM game_snapshots
                    ORDER BY game_id, COALESCE(source_updated_at, fetched_at, created_at) DESC
                )
                SELECT g.public_game_id, COALESCE(l.source_updated_at, l.fetched_at, l.created_at) AS detected_at,
                       l.runner_on_first, l.runner_on_second, l.runner_on_third,
                       l.first_base_runner_name, l.second_base_runner_name, l.third_base_runner_name,
                       l.first_base_runner_id, l.second_base_runner_id, l.third_base_runner_id
                FROM latest l
                JOIN games g ON g.id = l.game_id
                WHERE g.status = 'live' AND (
                    l.runner_on_first <> (l.first_base_runner_name IS NOT NULL OR l.first_base_runner_id IS NOT NULL)
                    OR l.runner_on_second <> (l.second_base_runner_name IS NOT NULL OR l.second_base_runner_id IS NOT NULL)
                    OR l.runner_on_third <> (l.third_base_runner_name IS NOT NULL OR l.third_base_runner_id IS NOT NULL)
                )
                ORDER BY detected_at DESC
                """, (rs, rowNum) -> new IssueView(
                        "주자 정보 불일치",
                        "warning",
                        text(rs, "public_game_id"),
                        "베이스 점유 여부와 주자 이름/ID가 일치하지 않습니다.",
                        runnerMismatchDetail(rs),
                        format(rs.getObject("detected_at", OffsetDateTime.class))
                )));

        OffsetDateTime since = now.minusHours(24);
        issues.addAll(jdbcTemplate.query("""
                SELECT g.public_game_id, n.event_type, n.error_message, COALESCE(n.sent_at, n.created_at) AS detected_at
                FROM notification_events n
                JOIN games g ON g.id = n.game_id
                WHERE n.delivery_status = 'failed'
                  AND n.created_at >= ?
                ORDER BY detected_at DESC
                LIMIT 100
                """, (rs, rowNum) -> new IssueView(
                        "알림 발송 실패",
                        "danger",
                        text(rs, "public_game_id"),
                        text(rs, "event_type") + " 알림 발송에 실패했습니다.",
                        defaultText(text(rs, "error_message"), "실패 사유가 기록되지 않았습니다."),
                        format(rs.getObject("detected_at", OffsetDateTime.class))
                ), since));
        return issues;
    }

    public List<TeamFilterView> notificationTeams() {
        return jdbcTemplate.query("""
                SELECT team_code, short_name
                FROM teams
                ORDER BY short_name ASC, team_code ASC
                """, (rs, rowNum) -> new TeamFilterView(
                text(rs, "team_code"),
                text(rs, "short_name")
        ));
    }

    public NotificationSearchView notificationHistory(
            LocalDate from,
            LocalDate to,
            String teamCode,
            String deliveryStatus,
            String gameId,
            String eventType
    ) {
        String occurredAt = "COALESCE(n.sent_at, n.created_at)";
        String fromClause = """
                FROM notification_events n
                JOIN games g ON g.id = n.game_id
                JOIN teams home ON home.id = g.home_team_id
                JOIN teams away ON away.id = g.away_team_id
                """;
        StringBuilder whereClause = new StringBuilder(" WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();

        if (from != null) {
            whereClause.append(" AND ").append(occurredAt).append(" >= ?");
            parameters.add(from.atStartOfDay(KST).toOffsetDateTime());
        }
        if (to != null) {
            whereClause.append(" AND ").append(occurredAt).append(" < ?");
            parameters.add(to.plusDays(1).atStartOfDay(KST).toOffsetDateTime());
        }
        if (teamCode != null && !teamCode.isBlank()) {
            whereClause.append(" AND (home.team_code = ? OR away.team_code = ?)");
            parameters.add(teamCode.trim());
            parameters.add(teamCode.trim());
        }
        if (deliveryStatus != null && !deliveryStatus.isBlank()) {
            whereClause.append(" AND LOWER(n.delivery_status) = ?");
            parameters.add(deliveryStatus.trim().toLowerCase(Locale.ROOT));
        }
        if (gameId != null && !gameId.isBlank()) {
            whereClause.append(" AND g.public_game_id ILIKE ?");
            parameters.add("%" + gameId.trim() + "%");
        }
        if (eventType != null && !eventType.isBlank()) {
            whereClause.append(" AND n.event_type ILIKE ?");
            parameters.add("%" + eventType.trim() + "%");
        }

        Object[] arguments = parameters.toArray();
        NotificationSearchCounts counts = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS total_count,
                       COUNT(*) FILTER (WHERE LOWER(n.delivery_status) = 'sent') AS sent_count,
                       COUNT(*) FILTER (WHERE LOWER(n.delivery_status) = 'failed') AS failed_count,
                       COUNT(*) FILTER (WHERE LOWER(n.delivery_status) = 'pending') AS pending_count
                """ + fromClause + whereClause, (rs, rowNum) -> new NotificationSearchCounts(
                rs.getLong("total_count"),
                rs.getLong("sent_count"),
                rs.getLong("failed_count"),
                rs.getLong("pending_count")
        ), arguments);

        List<NotificationHistoryView> items = jdbcTemplate.query("""
                SELECT g.public_game_id, g.game_date,
                       away.short_name AS away_team, home.short_name AS home_team,
                       n.event_type, n.title, n.body, n.delivery_status, n.error_message,
                       n.created_at, n.sent_at,
                       COALESCE(n.sent_at, n.created_at) AS occurred_at
                """ + fromClause + whereClause
                + "\nORDER BY COALESCE(n.sent_at, n.created_at) DESC, n.created_at DESC"
                + "\nLIMIT 500", (rs, rowNum) -> new NotificationHistoryView(
                text(rs, "public_game_id"),
                text(rs, "game_date"),
                text(rs, "away_team"),
                text(rs, "home_team"),
                text(rs, "event_type"),
                text(rs, "title"),
                text(rs, "body"),
                text(rs, "delivery_status"),
                text(rs, "error_message"),
                format(rs.getObject("created_at", OffsetDateTime.class)),
                format(rs.getObject("sent_at", OffsetDateTime.class)),
                format(rs.getObject("occurred_at", OffsetDateTime.class))
        ), arguments);

        NotificationSearchCounts safeCounts = counts == null
                ? new NotificationSearchCounts(0, 0, 0, 0)
                : counts;
        return new NotificationSearchView(
                items,
                safeCounts.total(),
                safeCounts.sent(),
                safeCounts.failed(),
                safeCounts.pending()
        );
    }

    private List<GameSummaryView> loadLiveGames() {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        return jdbcTemplate.query("""
                SELECT g.public_game_id, home.short_name AS home_team, away.short_name AS away_team,
                       g.home_score, g.away_score, g.inning_state,
                       COALESCE(s.source_updated_at, s.fetched_at, s.created_at, g.updated_at) AS refreshed_at
                FROM games g
                JOIN teams home ON home.id = g.home_team_id
                JOIN teams away ON away.id = g.away_team_id
                LEFT JOIN LATERAL (
                    SELECT source_updated_at, fetched_at, created_at
                    FROM game_snapshots
                    WHERE game_id = g.id
                    ORDER BY COALESCE(source_updated_at, fetched_at, created_at) DESC
                    LIMIT 1
                ) s ON true
                WHERE g.game_date = ? AND g.status = 'live'
                ORDER BY g.scheduled_at ASC NULLS LAST, g.public_game_id ASC
                """, (rs, rowNum) -> new GameSummaryView(
                        text(rs, "public_game_id"),
                        text(rs, "away_team"),
                        text(rs, "home_team"),
                        nullableInteger(rs, "away_score"),
                        nullableInteger(rs, "home_score"),
                        defaultText(text(rs, "inning_state"), "진행 중"),
                        format(rs.getObject("refreshed_at", OffsetDateTime.class))
                ), today);
    }

    private NotificationCounts loadTodayNotificationCounts() {
        LocalDate today = LocalDate.now(clock.withZone(KST));
        OffsetDateTime start = today.atStartOfDay(KST).toOffsetDateTime();
        OffsetDateTime end = today.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FILTER (WHERE delivery_status = 'sent') AS sent_count,
                       COUNT(*) FILTER (WHERE delivery_status = 'failed') AS failed_count
                FROM notification_events
                WHERE created_at >= ? AND created_at < ?
                """, (rs, rowNum) -> new NotificationCounts(
                        rs.getLong("sent_count"),
                        rs.getLong("failed_count")
                ), start, end);
    }

    private Optional<JobSummary> loadLatestJob() {
        List<JobSummary> jobs = jdbcTemplate.query("""
                SELECT job_type, status, COALESCE(finished_at, started_at, scheduled_at, updated_at) AS occurred_at,
                       last_error_message
                FROM crawl_jobs
                ORDER BY COALESCE(finished_at, started_at, scheduled_at, updated_at) DESC NULLS LAST
                LIMIT 1
                """, (rs, rowNum) -> new JobSummary(
                        text(rs, "job_type"),
                        text(rs, "status"),
                        format(rs.getObject("occurred_at", OffsetDateTime.class)),
                        text(rs, "last_error_message")
                ));
        return jobs.stream().findFirst();
    }

    private GameHeaderView mapGameHeader(ResultSet rs) throws SQLException {
        return new GameHeaderView(
                rs.getString("id"),
                text(rs, "public_game_id"),
                text(rs, "provider_game_id"),
                text(rs, "game_date"),
                format(rs.getObject("scheduled_at", OffsetDateTime.class)),
                text(rs, "stadium"),
                text(rs, "status"),
                text(rs, "away_team"),
                text(rs, "home_team"),
                nullableInteger(rs, "away_score"),
                nullableInteger(rs, "home_score"),
                text(rs, "inning_state"),
                format(rs.getObject("source_updated_at", OffsetDateTime.class)),
                format(rs.getObject("live_last_checked_at", OffsetDateTime.class)),
                format(rs.getObject("updated_at", OffsetDateTime.class))
        );
    }

    private SnapshotView mapSnapshot(ResultSet rs) throws SQLException {
        return new SnapshotView(
                nullableInteger(rs, "inning"),
                text(rs, "inning_half"),
                defaultText(text(rs, "inning_label"), "-"),
                nullableInteger(rs, "balls"),
                nullableInteger(rs, "strikes"),
                nullableInteger(rs, "outs"),
                runner(rs.getBoolean("runner_on_first"), text(rs, "first_base_runner_name")),
                runner(rs.getBoolean("runner_on_second"), text(rs, "second_base_runner_name")),
                runner(rs.getBoolean("runner_on_third"), text(rs, "third_base_runner_name")),
                text(rs, "current_pitcher_name"),
                text(rs, "current_batter_name"),
                nullableInteger(rs, "away_score"),
                nullableInteger(rs, "home_score"),
                format(rs.getObject("source_updated_at", OffsetDateTime.class)),
                format(rs.getObject("fetched_at", OffsetDateTime.class)),
                format(rs.getObject("created_at", OffsetDateTime.class))
        );
    }

    private NotificationView mapNotification(ResultSet rs) throws SQLException {
        return new NotificationView(
                text(rs, "event_type"),
                text(rs, "title"),
                text(rs, "body"),
                text(rs, "delivery_status"),
                text(rs, "error_message"),
                format(rs.getObject("created_at", OffsetDateTime.class)),
                format(rs.getObject("sent_at", OffsetDateTime.class))
        );
    }

    private String runnerMismatchDetail(ResultSet rs) throws SQLException {
        List<String> bases = new ArrayList<>();
        appendMismatch(bases, "1루", rs.getBoolean("runner_on_first"), text(rs, "first_base_runner_name"), text(rs, "first_base_runner_id"));
        appendMismatch(bases, "2루", rs.getBoolean("runner_on_second"), text(rs, "second_base_runner_name"), text(rs, "second_base_runner_id"));
        appendMismatch(bases, "3루", rs.getBoolean("runner_on_third"), text(rs, "third_base_runner_name"), text(rs, "third_base_runner_id"));
        return String.join(", ", bases);
    }

    private void appendMismatch(List<String> bases, String base, boolean occupied, String name, String id) {
        boolean hasIdentity = name != null || id != null;
        if (occupied != hasIdentity) {
            bases.add(base + "(점유=" + occupied + ", 이름=" + defaultText(name, "-") + ", ID=" + defaultText(id, "-") + ")");
        }
    }

    private String runner(boolean occupied, String name) {
        if (!occupied) {
            return "-";
        }
        return defaultText(name, "이름 없음");
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private String text(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null || value.isBlank() ? null : value;
    }

    private String format(OffsetDateTime value) {
        return value == null ? "-" : DATE_TIME_FORMAT.format(value.atZoneSameInstant(KST));
    }

    private String formatUptime(long uptimeMillis) {
        Duration duration = Duration.ofMillis(Math.max(0, uptimeMillis));
        long days = duration.toDays();
        long hours = duration.minusDays(days).toHours();
        long minutes = duration.minusDays(days).minusHours(hours).toMinutes();
        return (days > 0 ? days + "일 " : "") + hours + "시간 " + minutes + "분";
    }

    private String mask(String value) {
        if (value == null || value.isBlank()) {
            return "미설정";
        }
        String trimmed = value.trim();
        return trimmed.length() == 1 ? "*" : trimmed.charAt(0) + "*".repeat(Math.min(7, trimmed.length() - 1));
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record DashboardView(
            String serverStatus,
            String uptime,
            String databaseStatus,
            String databaseLatency,
            String schedulerStatus,
            String schedulerTone,
            String schedulerInterval,
            JobSummary lastJob,
            long notificationSent,
            long notificationFailed,
            List<GameSummaryView> liveGames,
            int issueCount,
            String adminUsername,
            String adminPassword,
            String databaseUsername
    ) {
    }

    public record GameSummaryView(
            String publicGameId,
            String awayTeam,
            String homeTeam,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            String refreshedAt
    ) {
    }

    public record JobSummary(String jobType, String status, String occurredAt, String errorMessage) {
        public String tone() {
            return "failed".equalsIgnoreCase(status) ? "danger" : "success";
        }
    }

    public record GameDetailView(
            GameHeaderView game,
            List<SnapshotView> snapshots,
            List<NotificationView> notifications,
            List<CrawlHistoryView> crawlHistory,
            List<IssueView> issues
    ) {
    }

    public record GameHeaderView(
            String id,
            String publicGameId,
            String providerGameId,
            String gameDate,
            String scheduledAt,
            String stadium,
            String status,
            String awayTeam,
            String homeTeam,
            Integer awayScore,
            Integer homeScore,
            String inningState,
            String sourceUpdatedAt,
            String lastCheckedAt,
            String updatedAt
    ) {
    }

    public record SnapshotView(
            Integer inning,
            String inningHalf,
            String inningLabel,
            Integer balls,
            Integer strikes,
            Integer outs,
            String firstBaseRunner,
            String secondBaseRunner,
            String thirdBaseRunner,
            String pitcher,
            String batter,
            Integer awayScore,
            Integer homeScore,
            String sourceUpdatedAt,
            String fetchedAt,
            String createdAt
    ) {
    }

    public record NotificationView(
            String eventType,
            String title,
            String body,
            String status,
            String errorMessage,
            String createdAt,
            String sentAt
    ) {
        public String tone() {
            return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
                case "sent" -> "success";
                case "failed" -> "danger";
                default -> "muted";
            };
        }
    }

    public record TeamFilterView(String code, String name) {
    }

    public record NotificationSearchView(
            List<NotificationHistoryView> items,
            long total,
            long sent,
            long failed,
            long pending
    ) {
    }

    public record NotificationHistoryView(
            String publicGameId,
            String gameDate,
            String awayTeam,
            String homeTeam,
            String eventType,
            String title,
            String body,
            String status,
            String errorMessage,
            String createdAt,
            String sentAt,
            String occurredAt
    ) {
        public String tone() {
            return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
                case "sent" -> "success";
                case "failed" -> "danger";
                case "pending" -> "warning";
                default -> "muted";
            };
        }

        public String statusLabel() {
            return switch (status == null ? "" : status.toLowerCase(Locale.ROOT)) {
                case "sent" -> "성공";
                case "failed" -> "실패";
                case "pending" -> "대기";
                default -> status == null ? "-" : status;
            };
        }
    }

    public record CrawlHistoryView(
            String jobType,
            String status,
            String startedAt,
            String finishedAt,
            String errorMessage
    ) {
    }

    public record IssueView(
            String type,
            String tone,
            String gameId,
            String title,
            String detail,
            String detectedAt
    ) {
    }

    private record NotificationCounts(long sent, long failed) {
    }

    private record NotificationSearchCounts(long total, long sent, long failed, long pending) {
    }
}
