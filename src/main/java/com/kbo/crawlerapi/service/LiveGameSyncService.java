package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;
import com.kbo.crawlerapi.service.NotificationEventService.EventDeliveryResult;
import com.kbo.crawlerapi.service.NotificationEventService.NotificationEventDraft;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LiveGameSyncService {

    private static final Logger log = LoggerFactory.getLogger(LiveGameSyncService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final LineScoreRepository lineScoreRepository;
    private final GameDetailImportService gameDetailImportService;
    private final NotificationEventService notificationEventService;
    private final LiveSyncProperties properties;
    private final Clock applicationClock;
    private final Map<UUID, Instant> nextRefreshAtByGameId = new ConcurrentHashMap<>();

    public LiveGameSyncService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            GameDetailImportService gameDetailImportService,
            NotificationEventService notificationEventService,
            LiveSyncProperties properties,
            Clock applicationClock
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.gameDetailImportService = gameDetailImportService;
        this.notificationEventService = notificationEventService;
        this.properties = properties;
        this.applicationClock = applicationClock;
    }

    public LocalDate todayKst() {
        return LocalDate.now(applicationClock.withZone(KST));
    }

    public LiveSyncSummary syncToday() {
        return sync(todayKst(), false);
    }

    public LiveSyncSummary sync(LocalDate date, boolean force) {
        LocalDate targetDate = date == null ? todayKst() : date;
        log.info("[LiveGameSync] started date={}", targetDate);
        List<Game> games = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(targetDate);
        List<Game> candidates = games.stream()
                .filter(game -> isCandidate(game, force))
                .toList();
        log.info("[LiveGameSync] candidate count={}", candidates.size());

        int updatedCount = 0;
        int eventCreatedCount = 0;
        int notificationSentCount = 0;
        int notificationSkippedCount = 0;
        int failedCount = 0;
        List<String> updatedGames = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Game candidate : candidates) {
            GameState before = GameState.from(candidate, latestSnapshot(candidate));
            try {
                gameDetailImportService.importGameDetail(candidate.getPublicGameId());
                Game after = gameRepository.findByPublicGameId(candidate.getPublicGameId()).orElseThrow();
                after.markLiveChecked(OffsetDateTime.now(applicationClock));
                boolean finalConfirmed = confirmFinalIfComplete(after);
                gameRepository.save(after);

                List<NotificationEventDraft> drafts = detectChanges(before, GameState.from(after, latestSnapshot(after)), after);
                if (!drafts.isEmpty()) {
                    updatedCount++;
                    updatedGames.add(after.getPublicGameId());
                }
                for (NotificationEventDraft draft : drafts) {
                    EventDeliveryResult delivery = notificationEventService.createAndDeliver(after, draft);
                    if (delivery.eventCreated()) {
                        eventCreatedCount++;
                        events.add(delivery.eventKey());
                    }
                    notificationSentCount += delivery.sentCount();
                    notificationSkippedCount += delivery.skippedCount();
                }
                nextRefreshAtByGameId.put(after.getId(), Instant.now(applicationClock).plus(ttlFor(after)));
            } catch (RuntimeException exception) {
                failedCount++;
                errors.add(candidate.getPublicGameId() + ": " + exception.getMessage());
                log.warn("[LiveGameSync] failed game id={} reason={}", candidate.getPublicGameId(), exception.getMessage());
                nextRefreshAtByGameId.put(candidate.getId(), Instant.now(applicationClock).plus(Duration.ofMinutes(1)));
            }
        }

        log.info("[LiveGameSync] updated count={}", updatedCount);
        log.info("[LiveGameSync] event created count={}", eventCreatedCount);
        log.info("[LiveGameSync] notification sent count={}", notificationSentCount);
        log.info("[LiveGameSync] notification skipped count={}", notificationSkippedCount);
        return new LiveSyncSummary(
                targetDate,
                games.size(),
                candidates.size(),
                updatedCount,
                eventCreatedCount,
                notificationSentCount,
                notificationSkippedCount,
                failedCount,
                updatedGames,
                events,
                errors
        );
    }

    private boolean isCandidate(Game game, boolean force) {
        if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
            return false;
        }
        if (game.getStatus() == GameStatus.FINAL && game.getFinalConfirmedAt() != null) {
            return false;
        }
        if (!force && !isActiveKstWindow() && !isNearScheduledStart(game)) {
            return false;
        }
        Instant nextRefreshAt = nextRefreshAtByGameId.get(game.getId());
        return force || nextRefreshAt == null || !Instant.now(applicationClock).isBefore(nextRefreshAt);
    }

    private boolean isActiveKstWindow() {
        LocalTime now = LocalTime.now(applicationClock.withZone(KST));
        return !now.isBefore(LocalTime.of(10, 0)) && !now.isAfter(LocalTime.of(23, 30));
    }

    private boolean isNearScheduledStart(Game game) {
        if (game.getScheduledAt() == null) {
            return false;
        }
        Instant scheduled = game.getScheduledAt().toInstant();
        Instant now = Instant.now(applicationClock);
        return !now.isBefore(scheduled.minus(Duration.ofHours(3))) && !now.isAfter(scheduled.plus(Duration.ofHours(6)));
    }

    private Duration ttlFor(Game game) {
        if (game.getStatus() == GameStatus.LIVE || game.getStatus() == GameStatus.SUSPENDED) {
            return properties.getLiveTtl();
        }
        if (game.getStatus() == GameStatus.FINAL && game.getFinalConfirmedAt() == null) {
            return properties.getFinalConfirmationTtl();
        }
        return properties.getPregameTtl();
    }

    private boolean confirmFinalIfComplete(Game game) {
        if (game.getStatus() != GameStatus.FINAL || game.getFinalConfirmedAt() != null) {
            return false;
        }
        if (game.getHomeScore() == null || game.getAwayScore() == null) {
            return false;
        }
        if (lineScoreRepository.countByGame_Id(game.getId()) <= 0) {
            return false;
        }
        return game.confirmFinal(OffsetDateTime.now(applicationClock));
    }

    private List<NotificationEventDraft> detectChanges(GameState before, GameState after, Game game) {
        List<NotificationEventDraft> drafts = new ArrayList<>();
        if (after.status() == GameStatus.LIVE
                && after.awayScore() != null
                && after.homeScore() != null
                && (changedInteger(before.awayScore(), after.awayScore()) || changedInteger(before.homeScore(), after.homeScore()))) {
            drafts.add(scoreDraft(game, before, after));
        }
        if (after.status() == GameStatus.LIVE
                && after.currentBatterName() != null
                && after.currentPitcherName() != null
                && baseCount(after) > baseCount(before)
                && nullSafe(after.outs()) <= nullSafe(before.outs())) {
            drafts.add(onBaseDraft(game, after));
        }
        return drafts;
    }

    private NotificationEventDraft canceledDraft(Game game) {
        String title = "%s vs %s 경기 취소".formatted(game.getAwayTeam().getShortName(), game.getHomeTeam().getShortName());
        String body = game.getCancelReason() == GameCancelReason.RAIN
                ? "오늘 %s 경기가 우천 취소되었습니다.".formatted(game.getStadium() == null ? "예정" : game.getStadium())
                : "오늘 경기가 취소 또는 연기되었습니다.";
        return draft(game, "GAME_CANCELED", "game:%s:canceled:%s".formatted(game.getId(), game.getGameDate()), title, body);
    }

    private NotificationEventDraft pitchersDraft(Game game) {
        String away = game.getAwayStartingPitcherName();
        String home = game.getHomeStartingPitcherName();
        String title = "%s vs %s 선발투수 공개".formatted(game.getAwayTeam().getShortName(), game.getHomeTeam().getShortName());
        String body = "%s vs %s".formatted(away == null ? "미정" : away, home == null ? "미정" : home);
        return draft(game, "STARTING_PITCHERS", "game:%s:pitchers:%s:%s".formatted(game.getId(), away, home), title, body);
    }

    private NotificationEventDraft lineupDraft(Game game) {
        String title = "%s 라인업 공개".formatted(game.getHomeTeam().getShortName());
        String body = "오늘 경기 선발 라인업이 공개되었습니다.";
        return draft(game, "LINEUP_AVAILABLE", "game:%s:lineup:%s".formatted(game.getId(), sha256(game.getLineupData())), title, body);
    }

    private NotificationEventDraft startedDraft(Game game) {
        String title = "%s vs %s 경기 시작".formatted(game.getAwayTeam().getShortName(), game.getHomeTeam().getShortName());
        return draft(game, "GAME_STARTED", "game:%s:started".formatted(game.getId()), title, "경기가 시작되었습니다.");
    }

    private NotificationEventDraft scoreDraft(Game game, GameState before, GameState after) {
        String inning = game.getInningState() == null ? "경기" : game.getInningState();
        int runCount = Math.max(
                1,
                Math.max(0, nullSafe(after.awayScore()) - nullSafe(before.awayScore()))
                        + Math.max(0, nullSafe(after.homeScore()) - nullSafe(before.homeScore()))
        );
        String result = liveEventResult(game, "득점");
        String title = "%s %d : %d %s".formatted(game.getAwayTeam().getShortName(), game.getAwayScore(), game.getHomeScore(), game.getHomeTeam().getShortName());
        return liveDraft(
                game,
                "SCORE_CHANGED",
                "game:%s:score:%d-%d:inning:%s:batter:%s:pitcher:%s".formatted(
                        game.getId(),
                        game.getAwayScore(),
                        game.getHomeScore(),
                        inning,
                        safeKey(after.currentBatterName()),
                        safeKey(after.currentPitcherName())
                ),
                title,
                liveEventBody(after, result, runCount),
                after,
                result,
                runCount
        );
    }

    private NotificationEventDraft onBaseDraft(Game game, GameState after) {
        String inning = game.getInningState() == null ? "경기" : game.getInningState();
        String result = liveEventResult(game, "출루");
        return liveDraft(
                game,
                "ON_BASE",
                "game:%s:on-base:inning:%s:bases:%s:batter:%s:pitcher:%s".formatted(
                        game.getId(),
                        inning,
                        baseKey(after),
                        safeKey(after.currentBatterName()),
                        safeKey(after.currentPitcherName())
                ),
                "출루",
                liveEventBody(after, result, null),
                after,
                result,
                null
        );
    }

    private NotificationEventDraft finalDraft(Game game) {
        String title = "%s %d : %d %s 경기 종료".formatted(game.getAwayTeam().getShortName(), game.getAwayScore(), game.getHomeScore(), game.getHomeTeam().getShortName());
        return draft(
                game,
                "GAME_FINAL",
                "game:%s:final:%d-%d".formatted(game.getId(), game.getAwayScore(), game.getHomeScore()),
                title,
                "최종 스코어가 확정되었습니다."
        );
    }

    private NotificationEventDraft draft(Game game, String eventType, String eventKey, String title, String body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("gameId", game.getId().toString());
        payload.put("publicGameId", game.getPublicGameId());
        payload.put("eventType", eventType);
        payload.put("homeTeamId", game.getHomeTeam().getTeamCode());
        payload.put("awayTeamId", game.getAwayTeam().getTeamCode());
        payload.put("homeScore", game.getHomeScore());
        payload.put("awayScore", game.getAwayScore());
        payload.put("status", game.getStatus().getApiValue());
        payload.put("gameDate", game.getGameDate().toString());
        payload.put("deepLink", "kboscore://games/" + game.getPublicGameId());
        return new NotificationEventDraft(eventType, eventKey, title, body, payload);
    }

    private NotificationEventDraft liveDraft(
            Game game,
            String eventType,
            String eventKey,
            String title,
            String body,
            GameState state,
            String result,
            Integer runCount
    ) {
        NotificationEventDraft draft = draft(game, eventType, eventKey, title, body);
        draft.payload().put("batterName", state.currentBatterName());
        draft.payload().put("pitcherName", state.currentPitcherName());
        draft.payload().put("result", result);
        draft.payload().put("runCount", runCount);
        return draft;
    }

    private GameSnapshot latestSnapshot(Game game) {
        if (gameSnapshotRepository == null || game == null || game.getId() == null) {
            return null;
        }
        return gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId()).orElse(null);
    }

    private String liveEventBody(GameState state, String result, Integer runCount) {
        String playText = state.currentBatterName() == null || state.currentPitcherName() == null
                ? result + "."
                : "%s 이 %s 을 상대로 %s.".formatted(state.currentBatterName(), state.currentPitcherName(), result);
        return runCount == null ? playText : playText + "\n" + runCount + "득점";
    }

    private String liveEventResult(Game game, String fallback) {
        String text = String.join(" ",
                game.getStatusReason() == null ? "" : game.getStatusReason(),
                game.getInningState() == null ? "" : game.getInningState()
        );
        for (String method : List.of("고의사구", "사구", "볼넷", "홈런", "적시타", "안타", "2루타", "3루타", "희생플라이", "실책")) {
            if (text.contains(method)) {
                return method;
            }
        }
        return fallback;
    }

    private int baseCount(GameState state) {
        return (state.runnerOnFirst() ? 1 : 0) + (state.runnerOnSecond() ? 1 : 0) + (state.runnerOnThird() ? 1 : 0);
    }

    private String baseKey(GameState state) {
        return "%s%s%s".formatted(state.runnerOnFirst() ? "1" : "-", state.runnerOnSecond() ? "2" : "-", state.runnerOnThird() ? "3" : "-");
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeKey(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private boolean changedText(String before, String after) {
        return after != null && !after.isBlank() && !java.util.Objects.equals(before, after);
    }

    private boolean changedInteger(Integer before, Integer after) {
        return after != null && !java.util.Objects.equals(before, after);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record GameState(
            GameStatus status,
            boolean isCancelled,
            boolean isPostponed,
            Integer homeScore,
            Integer awayScore,
            String homeStartingPitcherName,
            String awayStartingPitcherName,
            String lineupHash,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            String currentPitcherName,
            String currentBatterName
    ) {
        static GameState from(Game game, GameSnapshot snapshot) {
            return new GameState(
                    game.getStatus(),
                    game.isCancelled(),
                    game.isPostponed(),
                    game.getHomeScore(),
                    game.getAwayScore(),
                    game.getHomeStartingPitcherName(),
                    game.getAwayStartingPitcherName(),
                    game.getLineupData() == null ? null : String.valueOf(game.getLineupData().hashCode()),
                    snapshot == null ? null : snapshot.getOuts(),
                    snapshot != null && snapshot.isRunnerOnFirst(),
                    snapshot != null && snapshot.isRunnerOnSecond(),
                    snapshot != null && snapshot.isRunnerOnThird(),
                    clean(snapshot == null ? null : snapshot.getCurrentPitcherName()),
                    clean(snapshot == null ? null : snapshot.getCurrentBatterName())
            );
        }

        private static String clean(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }

        boolean cancelledOrPostponed() {
            return isCancelled || isPostponed || status == GameStatus.CANCELLED || status == GameStatus.POSTPONED;
        }
    }

    public record LiveSyncSummary(
            LocalDate date,
            int scannedCount,
            int candidateCount,
            int updatedCount,
            int eventCreatedCount,
            int notificationSentCount,
            int notificationSkippedCount,
            int failedCount,
            List<String> updatedGames,
            List<String> events,
            List<String> errors
    ) {
    }
}
