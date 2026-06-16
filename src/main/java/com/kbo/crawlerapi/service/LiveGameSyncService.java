package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.config.LiveSyncProperties;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameEventReadRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.service.NotificationEventService.EventDeliveryResult;
import com.kbo.crawlerapi.service.NotificationEventService.NotificationEventDraft;
import com.kbo.crawlerapi.service.OnBasePlayDetailExtractor.OnBasePlayContext;
import com.kbo.crawlerapi.service.ScoringPlayDetailExtractor.ScoringPlayContext;
import com.kbo.crawlerapi.service.ScoringPlayNotificationFormatter.NotificationText;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LiveGameSyncService {

    private static final Logger log = LoggerFactory.getLogger(LiveGameSyncService.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CANCELLED_GAME_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Map<String, String> TEAM_DISPLAY_NAMES = Map.ofEntries(
            Map.entry("hanwha", "한화"),
            Map.entry("lotte", "롯데"),
            Map.entry("lg", "LG"),
            Map.entry("kt", "kt"),
            Map.entry("ssg", "SSG"),
            Map.entry("kia", "KIA"),
            Map.entry("nc", "NC"),
            Map.entry("doosan", "두산"),
            Map.entry("samsung", "삼성"),
            Map.entry("kiwoom", "키움")
    );

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final GameEventReadRepository gameEventReadRepository;
    private final GameDetailImportService gameDetailImportService;
    private final KboScheduleImportService kboScheduleImportService;
    private final NotificationEventService notificationEventService;
    private final TeamRankService teamRankService;
    private final LiveSyncProperties properties;
    private final Clock applicationClock;
    private final Map<UUID, Instant> nextRefreshAtByGameId = new ConcurrentHashMap<>();

    public LiveGameSyncService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            GameDetailImportService gameDetailImportService,
            KboScheduleImportService kboScheduleImportService,
            NotificationEventService notificationEventService,
            TeamRankService teamRankService,
            LiveSyncProperties properties,
            Clock applicationClock
    ) {
        this(
                gameRepository,
                gameSnapshotRepository,
                null,
                gameDetailImportService,
                kboScheduleImportService,
                notificationEventService,
                teamRankService,
                properties,
                applicationClock
        );
    }

    @Autowired
    public LiveGameSyncService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            GameEventReadRepository gameEventReadRepository,
            GameDetailImportService gameDetailImportService,
            KboScheduleImportService kboScheduleImportService,
            NotificationEventService notificationEventService,
            TeamRankService teamRankService,
            LiveSyncProperties properties,
            Clock applicationClock
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.gameEventReadRepository = gameEventReadRepository;
        this.gameDetailImportService = gameDetailImportService;
        this.kboScheduleImportService = kboScheduleImportService;
        this.notificationEventService = notificationEventService;
        this.teamRankService = teamRankService;
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
        List<Game> gamesBeforeScheduleRefresh = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(targetDate);
        Map<String, GameState> beforeScheduleStates = new HashMap<>();
        for (Game game : gamesBeforeScheduleRefresh) {
            beforeScheduleStates.put(game.getPublicGameId(), GameState.from(game, latestSnapshot(game)));
        }

        if (gamesBeforeScheduleRefresh.stream().anyMatch(game -> isLiveLike(game.getStatus()))) {
            log.debug("[LiveGameSync] skipped schedule refresh before detail import because live games are already present date={}", targetDate);
        } else {
            refreshScheduleBeforeDetailImport(targetDate);
        }

        List<Game> games = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(targetDate);
        List<Game> candidates = new ArrayList<>();
        for (Game game : games) {
            String skipReason = detailImportSkipReason(game, force);
            boolean attempted = skipReason == null;
            logCandidateDiagnostics(game, attempted, skipReason);
            if (attempted) {
                candidates.add(game);
            }
        }
        log.info("[LiveGameSync] candidate count={}", candidates.size());

        int updatedCount = 0;
        int eventCreatedCount = 0;
        int notificationSentCount = 0;
        int notificationSkippedCount = 0;
        int failedCount = 0;
        List<String> updatedGames = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        for (Game game : games) {
            GameState before = beforeScheduleStates.get(game.getPublicGameId());
            if (before == null || !isScheduleCancellationTransition(before.status(), game.getStatus())) {
                continue;
            }
            log.info(
                    "[LiveGameSync] schedule-level cancellation detected game={} previousStatus={} scheduleStatus={} cancelReason={} rawCancelText={}",
                    game.getPublicGameId(),
                    before.status(),
                    game.getStatus(),
                    game.getCancelReason(),
                    game.getRawCancelText()
            );
            NotificationEventDraft draft = cancelledDraft(game, game.getStatus());
            EventDeliveryResult delivery = notificationEventService.createAndDeliver(game, draft);
            updatedCount++;
            updatedGames.add(game.getPublicGameId());
            if (delivery.eventCreated()) {
                eventCreatedCount++;
                events.add(delivery.eventKey());
                log.info(
                        "[LiveGameSync] cancellation event created game={} eventKey={}",
                        game.getPublicGameId(),
                        delivery.eventKey()
                );
            }
            notificationSentCount += delivery.sentCount();
            notificationSkippedCount += delivery.skippedCount();
            log.info(
                    "[LiveGameSync] cancellation notification sent/skipped game={} eventKey={} sent={} skipped={} created={}",
                    game.getPublicGameId(),
                    delivery.eventKey(),
                    delivery.sentCount(),
                    delivery.skippedCount(),
                    delivery.eventCreated()
            );
            nextRefreshAtByGameId.put(game.getId(), Instant.now(applicationClock).plus(ttlFor(game)));
        }

        for (Game candidate : candidates) {
            GameState before = beforeScheduleStates.get(candidate.getPublicGameId());
            if (before == null) {
                before = GameState.from(candidate, latestSnapshot(candidate));
            }
            try {
                try {
                    gameDetailImportService.importGameDetail(candidate.getPublicGameId());
                } catch (RuntimeException exception) {
                    if (isDetailParseFailure(exception)) {
                        log.warn(
                                "[LiveGameSync] detail parse failure game={} reason={}",
                                candidate.getPublicGameId(),
                                exception.getMessage()
                        );
                    } else {
                        log.warn(
                                "[LiveGameSync] detail import failure game={} reason={}",
                                candidate.getPublicGameId(),
                                exception.getMessage()
                        );
                    }
                    throw exception;
                }
                Game after = gameRepository.findByPublicGameId(candidate.getPublicGameId()).orElseThrow();
                after.markLiveChecked(OffsetDateTime.now(applicationClock));
                boolean finalConfirmed = confirmFinalIfComplete(after);
                gameRepository.save(after);
                if (becameFinalOrFinalConfirmed(before, after, finalConfirmed)) {
                    teamRankService.refreshSeasonRankingsSafely(after.getGameDate().getYear());
                }

                List<NotificationEventDraft> drafts = detectChanges(
                        before,
                        GameState.from(after, latestSnapshot(after)),
                        after
                );
                if (!drafts.isEmpty()) {
                    updatedCount++;
                    updatedGames.add(after.getPublicGameId());
                }
                for (NotificationEventDraft draft : drafts) {
                    log.info(
                            "[Notifications] notification built at={} eventType={} eventKey={} publicGameId={}",
                            Instant.now(applicationClock),
                            draft.eventType(),
                            draft.eventKey(),
                            after.getPublicGameId()
                    );
                    EventDeliveryResult delivery = notificationEventService.createAndDeliver(after, draft);
                    if (delivery.eventCreated()) {
                        eventCreatedCount++;
                        events.add(delivery.eventKey());
                        if (NotificationEventService.EVENT_GAME_CANCELLED.equals(draft.eventType())) {
                            log.info(
                                    "[LiveGameSync] cancellation event created game={} eventKey={}",
                                    after.getPublicGameId(),
                                    delivery.eventKey()
                            );
                        }
                    }
                    notificationSentCount += delivery.sentCount();
                    notificationSkippedCount += delivery.skippedCount();
                    if (NotificationEventService.EVENT_GAME_CANCELLED.equals(draft.eventType())) {
                        log.info(
                                "[LiveGameSync] cancellation notification sent/skipped game={} eventKey={} sent={} skipped={} created={}",
                                after.getPublicGameId(),
                                delivery.eventKey(),
                                delivery.sentCount(),
                                delivery.skippedCount(),
                                delivery.eventCreated()
                        );
                    }
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

    private void refreshScheduleBeforeDetailImport(LocalDate targetDate) {
        if (kboScheduleImportService == null) {
            return;
        }
        try {
            DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(targetDate);
            log.info(
                    "[LiveGameSync] schedule refresh before detail import date={} processed={} updated={} skipped={} failures={}",
                    targetDate,
                    result.gameProcessedCount(),
                    result.gameUpdatedCount(),
                    result.skippedRowCount(),
                    result.failureCount()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[LiveGameSync] schedule refresh before detail import failed date={} reason={}",
                    targetDate,
                    exception.getMessage()
            );
        }
    }

    private boolean isDetailParseFailure(RuntimeException exception) {
        String message = exception.getMessage();
        if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("parse")) {
            return true;
        }
        Throwable cause = exception.getCause();
        while (cause != null) {
            String causeMessage = cause.getMessage();
            if (causeMessage != null && causeMessage.toLowerCase(java.util.Locale.ROOT).contains("parse")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private String detailImportSkipReason(Game game, boolean force) {
        if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
            return "missing-provider-game-id";
        }
        if (shouldSkipFinalConfirmed(game)) {
            return "final-confirmed";
        }
        if (!force && !isActiveKstWindow() && !isNearScheduledStart(game) && !hasScheduledStartReached(game)) {
            return "outside-active-window-and-pregame-eligibility";
        }
        Instant nextRefreshAt = nextRefreshAtByGameId.get(game.getId());
        Instant now = Instant.now(applicationClock);
        boolean refreshDue = force || nextRefreshAt == null || !now.isBefore(nextRefreshAt);
        if (!refreshDue) {
            return "ttl-not-due";
        }
        if (isScheduleCancellationTarget(game.getStatus())) {
            return "schedule-cancellation-target";
        }
        if (!shouldRunDetailImport(game)) {
            return "not-live-like";
        }
        return null;
    }

    private void logCandidateDiagnostics(Game game, boolean detailImportAttempted, String skipReason) {
        log.info(
                "[LiveGameSync] candidate diagnostics publicGameId={} providerGameId={} storedStatus={} storedStatusReason={} scheduledAt={} gameDate={} detailImport={} skipReason={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getStatus(),
                game.getStatusReason(),
                game.getScheduledAt(),
                game.getGameDate(),
                detailImportAttempted ? "attempted" : "skipped",
                skipReason
        );
    }

    private boolean isCandidate(Game game, boolean force) {
        if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
            log.debug("[LiveGameSync] skipped candidate game={} reason=missing-provider-game-id", game.getPublicGameId());
            return false;
        }
        if (shouldSkipFinalConfirmed(game)) {
            log.debug("[LiveGameSync] skipped candidate game={} reason=final-confirmed", game.getPublicGameId());
            return false;
        }
        if (!force && !isActiveKstWindow() && !isNearScheduledStart(game) && !hasScheduledStartReached(game)) {
            log.debug(
                    "[LiveGameSync] skipped candidate game={} reason=outside-active-window-and-pregame-eligibility scheduledAt={} now={}",
                    game.getPublicGameId(),
                    game.getScheduledAt(),
                    Instant.now(applicationClock)
            );
            return false;
        }
        Instant nextRefreshAt = nextRefreshAtByGameId.get(game.getId());
        Instant now = Instant.now(applicationClock);
        boolean refreshDue = force || nextRefreshAt == null || !now.isBefore(nextRefreshAt);
        if (!refreshDue) {
            log.debug(
                    "[LiveGameSync] skipped candidate game={} reason=ttl-not-due now={} nextRefreshAt={}",
                    game.getPublicGameId(),
                    now,
                    nextRefreshAt
            );
        }
        return refreshDue;
    }

    private boolean shouldRunDetailImport(Game game) {
        if (isLiveLike(game.getStatus())) {
            return true;
        }
        if (game.getStatus() == GameStatus.FINAL && !shouldSkipFinalConfirmed(game)) {
            return true;
        }
        if ((game.getStatus() == GameStatus.SCHEDULED || game.getStatus() == GameStatus.UNKNOWN)
                && hasScheduledStartReached(game)) {
            log.info(
                    "[LiveGameSync] selected detail import game={} status={} scheduledAt={} now={} reason=scheduled_start_reached",
                    game.getPublicGameId(),
                    game.getStatus(),
                    game.getScheduledAt(),
                    Instant.now(applicationClock)
            );
            return true;
        }
        log.debug(
                "[LiveGameSync] skipped detail import game={} reason=not-live-like status={}",
                game.getPublicGameId(),
                game.getStatus()
        );
        return false;
    }

    private boolean hasScheduledStartReached(Game game) {
        return game.getScheduledAt() != null && !Instant.now(applicationClock).isBefore(game.getScheduledAt().toInstant());
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
        Instant eligibleFrom = scheduled.minus(properties.getPregameEligibilityWindow());
        long minutesUntilStart = Duration.between(now, scheduled).toMinutes();
        boolean eligible = !now.isBefore(eligibleFrom) && !now.isAfter(scheduled.plus(Duration.ofHours(6)));
        log.debug(
                "[LiveGameSync] scheduled eligibility game={} eligible={} scheduledAt={} now={} minutesUntilStart={} eligibleFrom={}",
                game.getPublicGameId(),
                eligible,
                scheduled,
                now,
                minutesUntilStart,
                eligibleFrom
        );
        return eligible;
    }

    private Duration ttlFor(Game game) {
        if (game.getStatus() == GameStatus.LIVE || game.getStatus() == GameStatus.SUSPENDED) {
            return properties.getLiveTtl();
        }
        if (game.getStatus() == GameStatus.FINAL && !shouldSkipFinalConfirmed(game)) {
            return properties.getFinalConfirmationTtl();
        }
        return properties.getPregameTtl();
    }

    private boolean confirmFinalIfComplete(Game game) {
        if (game.getStatus() != GameStatus.FINAL || game.getFinalConfirmedAt() != null) {
            return false;
        }
        if (!hasReliableFinalStatusReason(game.getStatusReason())) {
            log.info(
                    "[LiveGameSync] final confirmation deferred game={} reason=weak-final-marker statusReason={}",
                    game.getPublicGameId(),
                    game.getStatusReason()
            );
            return false;
        }
        return game.confirmFinal(OffsetDateTime.now(applicationClock));
    }

    private boolean shouldSkipFinalConfirmed(Game game) {
        if (game.getStatus() != GameStatus.FINAL || game.getFinalConfirmedAt() == null) {
            return false;
        }
        if (game.getGameDate().equals(LocalDate.now(applicationClock.withZone(KST)))
                && !hasReliableFinalStatusReason(game.getStatusReason())) {
            return false;
        }
        return true;
    }

    private boolean isStrongFinal(Game game) {
        return game.getStatus() == GameStatus.FINAL
                && game.getFinalConfirmedAt() != null
                && hasReliableFinalStatusReason(game.getStatusReason());
    }

    private static boolean hasReliableFinalStatusReason(String statusReason) {
        if (statusReason == null || statusReason.isBlank()) {
            return false;
        }
        String lower = statusReason.toLowerCase(java.util.Locale.ROOT);
        String collapsed = statusReason.replace(" ", "");
        return statusReason.equals("GAME_RESULT_CK=1")
                || collapsed.contains("경기종료")
                || collapsed.equals("종료")
                || lower.contains("final")
                || lower.contains("ended")
                || lower.contains("completed");
    }

    private boolean becameFinalOrFinalConfirmed(GameState before, Game after, boolean finalConfirmed) {
        return finalConfirmed
                || (before.status() != GameStatus.FINAL
                && after.getStatus() == GameStatus.FINAL
                && after.getFinalConfirmedAt() != null);
    }

    private List<NotificationEventDraft> detectChanges(
            GameState before,
            GameState after,
            Game game
    ) {
        List<NotificationEventDraft> drafts = new ArrayList<>();
        if (before.status() != GameStatus.FINAL && !isLiveLike(before.status()) && isLiveLike(after.status())) {
            drafts.add(startedDraft(game));
        }
        if (isCancellationTransition(before.status(), after.status())) {
            drafts.add(cancelledDraft(game, after.status()));
        }
        if (isInterruptionTransition(before, after)) {
            drafts.add(interruptedDraft(game));
        }
        if (isResumeTransition(before.status(), after.status())) {
            drafts.add(resumedDraft(game));
        }
        if (isLiveLike(before.status()) && after.status() == GameStatus.FINAL && isStrongFinal(game)) {
            drafts.add(finalDraft(game));
        }
        if (isLiveLike(before.status()) && isLiveLike(after.status()) && inningChanged(before, after)) {
            drafts.add(inningChangeDraft(game, after));
        }
        String leadChangeEventTeamId = leadChangeEventTeamId(game, before, after);
        if (leadChangeEventTeamId != null) {
            drafts.add(leadChangeDraft(game, before, after, leadChangeEventTeamId));
        }
        if (after.status() == GameStatus.LIVE
                && after.awayScore() != null
                && after.homeScore() != null
                && scoreIncreased(before, after)) {
            drafts.add(scoreDraft(game, before, after));
        }
        if (after.status() == GameStatus.LIVE
                && baseCount(after) > baseCount(before)
                && nullSafe(after.outs()) <= nullSafe(before.outs())) {
            drafts.add(onBaseDraft(game, before, after));
        }
        return drafts;
    }

    private NotificationEventDraft cancelledDraft(Game game, GameStatus cancelledStatus) {
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_GAME_CANCELLED,
                "game:%s:game-cancelled".formatted(game.getId()),
                "경기 취소",
                cancellationBody(game, cancelledStatus)
        );
        draft.payload().put("cancelReason", game.getCancelReason() == null ? null : game.getCancelReason().getApiValue());
        draft.payload().put("rawCancelText", game.getRawCancelText());
        return draft;
    }

    private NotificationEventDraft interruptedDraft(Game game) {
        String reason = normalizedInterruptionReason(game);
        String matchup = "%s vs %s".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        String body = isRainInterruption(reason)
                ? "%s 경기가 우천으로 중단되었습니다.".formatted(matchup)
                : "%s 경기가 중단되었습니다.".formatted(matchup);
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_GAME_INTERRUPTED,
                "game:%s:interrupted:%s".formatted(game.getId(), safeKey(reason)),
                "경기 중단",
                body
        );
        draft.payload().put("statusReason", game.getStatusReason());
        return draft;
    }

    private NotificationEventDraft resumedDraft(Game game) {
        String matchup = "%s vs %s".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        return draft(
                game,
                NotificationEventService.EVENT_GAME_RESUMED,
                "game:%s:resumed".formatted(game.getId()),
                "경기 재개",
                "%s 경기가 재개되었습니다.".formatted(matchup)
        );
    }

    private NotificationEventDraft pitchersDraft(Game game) {
        String away = game.getAwayStartingPitcherName();
        String home = game.getHomeStartingPitcherName();
        String title = "%s vs %s 선발투수 공개".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        String body = "%s vs %s".formatted(away == null ? "미정" : away, home == null ? "미정" : home);
        return draft(game, "STARTING_PITCHERS", "game:%s:pitchers:%s:%s".formatted(game.getId(), away, home), title, body);
    }

    private NotificationEventDraft lineupDraft(Game game) {
        String title = "%s 라인업 공개".formatted(teamShortName(game, game.getHomeTeam().getTeamCode()));
        String body = "오늘 경기 선발 라인업이 공개되었습니다.";
        return draft(game, "LINEUP_AVAILABLE", "game:%s:lineup:%s".formatted(game.getId(), sha256(game.getLineupData())), title, body);
    }

    private NotificationEventDraft startedDraft(Game game) {
        String title = "%s vs %s 경기 시작".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        return draft(game, NotificationEventService.EVENT_GAME_START, "game:%s:game-start".formatted(game.getId()), title, "경기가 시작되었습니다.");
    }

    private NotificationEventDraft scoreDraft(Game game, GameState before, GameState after) {
        int runCount = Math.max(
                1,
                Math.max(0, nullSafe(after.awayScore()) - nullSafe(before.awayScore()))
                        + Math.max(0, nullSafe(after.homeScore()) - nullSafe(before.homeScore()))
        );

        String batterName = before.currentBatterName();
        String pitcherName = before.currentPitcherName();
        String result = liveEventResult(game, "득점");
        String eventTeamId = scoringTeamId(game, before, after);
        ScoringPlayContext scoringContext = scoringPlayContext(game, before, after, eventTeamId);
        ScoringPlayResolution scoringPlayResolution = scoringPlayResolution(game, scoringContext);
        ScoringPlayDetail scoringPlayDetail = scoringPlayResolution.detail();
        NotificationText detailedText = ScoringPlayNotificationFormatter.scoreChangeText(scoringPlayDetail).orElse(null);
        NotificationText fallbackText = scoringFallbackText(scoringContext);
        String formattedMessage = detailedText == null ? fallbackText.body() : detailedText.body();
        boolean fallbackUsed = detailedText == null;

        NotificationEventDraft draft = liveDraft(
                game,
                NotificationEventService.EVENT_SCORE_CHANGED,
                "game:%s:score:%d-%d".formatted(
                        game.getId(),
                        game.getAwayScore(),
                        game.getHomeScore()
                ),
                detailedText == null ? fallbackText.title() : detailedText.title(),
                formattedMessage,
                batterName,
                pitcherName,
                result,
                runCount,
                eventTeamId
        );
        addScoringPlayPayload(draft, scoringPlayDetail);
        log.info(
                "[ScoringFormatter] gameId={} inning={} inningHalf={} scoreDelta={} selectedEventType={} selectedEventText={} runScoredEventCount={} formattedMessage={} fallbackUsed={}",
                game.getPublicGameId(),
                scoringContext == null ? null : scoringContext.inning(),
                scoringContext == null ? null : scoringContext.inningHalf(),
                scoringContext == null ? runCount : scoringContext.scoreDelta(),
                scoringPlayDetail == null ? null : scoringPlayDetail.selectedEventType(),
                scoringPlayDetail == null ? null : scoringPlayDetail.selectedEventText(),
                scoringPlayResolution.runScoredEventCount(),
                formattedMessage,
                fallbackUsed
        );
        return draft;
    }

    private NotificationEventDraft onBaseDraft(Game game, GameState before, GameState after) {
        Instant detectedAt = Instant.now(applicationClock);
        String inning = game.getInningState() == null ? "경기" : game.getInningState();

        String batterName = before.currentBatterName();
        String pitcherName = before.currentPitcherName();
        String result = liveEventResult(game, "출루");
        String eventTeamId = battingTeamId(game);
        OnBasePlayContext onBaseContext = onBasePlayContext(game, before, after, eventTeamId);
        OnBaseDetailResolution onBaseDetailResolution = onBaseDetail(game, onBaseContext);
        NotificationText onBaseText = OnBaseNotificationFormatter.text(onBaseDetailResolution.detail()).orElse(null);
        Instant builtAt = Instant.now(applicationClock);

        NotificationEventDraft draft = liveDraft(
                game,
                NotificationEventService.EVENT_ON_BASE,
                "game:%s:on-base:inning:%s:bases:%s:batter:%s:pitcher:%s:result:%s".formatted(
                        game.getId(),
                        inning,
                        baseKey(after),
                        safeKey(batterName),
                        safeKey(pitcherName),
                        safeKey(result)
                ),
                onBaseText == null ? "출루" : onBaseText.title(),
                onBaseText == null ? onBaseBody(game, batterName, result) : onBaseText.body(),
                batterName,
                pitcherName,
                result,
                null,
                eventTeamId
        );
        addOnBaseDetailPayload(draft, onBaseDetailResolution);
        log.debug(
                "[LiveGameSync] ON_BASE diagnostics gameId={} eventKey={} previous inning={}/{} batter={} pitcher={} bases={} current inning={}/{} batter={} pitcher={} bases={} eventBatter={} eventPitcher={} eventResult={} eventTeamId={}",
                game.getPublicGameId(),
                draft.eventKey(),
                before.inning(),
                before.inningHalf(),
                before.currentBatterName(),
                before.currentPitcherName(),
                baseKey(before),
                after.inning(),
                after.inningHalf(),
                after.currentBatterName(),
                after.currentPitcherName(),
                baseKey(after),
                batterName,
                pitcherName,
                result,
                eventTeamId
        );
        log.info(
                "[Notifications] event detected at={} notification built at={} eventType={} eventKey={} publicGameId={} detailSource={} detailExtractionDurationMs={}",
                detectedAt,
                builtAt,
                draft.eventType(),
                draft.eventKey(),
                game.getPublicGameId(),
                onBaseDetailResolution.detailSource(),
                onBaseDetailResolution.durationMs()
        );
        return draft;
    }

    private NotificationEventDraft finalDraft(Game game) {
        String title = "%s %d : %d %s 경기 종료".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), game.getAwayScore(), game.getHomeScore(), teamShortName(game, game.getHomeTeam().getTeamCode()));
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_GAME_END,
                "game:%s:game-end".formatted(game.getId()),
                title,
                "최종 스코어가 확정되었습니다."
        );
        String winningTeamId = winningTeamId(game);
        if (winningTeamId != null) {
            draft.payload().put("winningTeamId", winningTeamId);
            draft.payload().put("losingTeamId", losingTeamId(game));
        }
        return draft;
    }

    private NotificationEventDraft inningChangeDraft(Game game, GameState after) {
        String label = inningDisplayLabel(after);
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_INNING_CHANGED,
                "game:%s:inning-change:%s:%s".formatted(game.getId(), after.inning(), safeKey(after.inningHalf())),
                label,
                "%s로 전환되었습니다.".formatted(label)
        );
        draft.payload().put("inning", after.inning());
        draft.payload().put("inningHalf", after.inningHalf());
        draft.payload().put("inningLabel", label);
        return draft;
    }

    private NotificationEventDraft leadChangeDraft(Game game, GameState before, GameState after, String eventTeamId) {
        boolean tied = nullSafe(after.awayScore()) == nullSafe(after.homeScore());
        ScoringPlayContext scoringContext = scoringPlayContext(game, before, after, eventTeamId);
        ScoringPlayDetail scoringPlayDetail = scoringPlayResolution(game, scoringContext).detail();
        NotificationText detailedText = ScoringPlayNotificationFormatter
                .leadChangeText(scoringPlayDetail, before.awayScore(), before.homeScore())
                .orElse(null);
        NotificationText fallbackText = leadChangeFallbackText(scoringContext, before.awayScore(), before.homeScore());
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_LEAD_CHANGED,
                "game:%s:lead-change:%s:%d-%d".formatted(game.getId(), eventTeamId, nullSafe(after.awayScore()), nullSafe(after.homeScore())),
                detailedText == null ? fallbackText.title() : detailedText.title(),
                detailedText == null ? fallbackText.body() : detailedText.body()
        );
        draft.payload().put("previousAwayScore", before.awayScore());
        draft.payload().put("previousHomeScore", before.homeScore());
        draft.payload().put("awayScore", after.awayScore());
        draft.payload().put("homeScore", after.homeScore());
        draft.payload().put(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, eventTeamId);
        addScoringPlayPayload(draft, scoringPlayDetail);
        if (tied) {
            draft.payload().put("leadChangeReason", "TIED_GAME");
        }
        return draft;
    }

    private ScoringPlayResolution scoringPlayResolution(Game game, ScoringPlayContext context) {
        if (gameEventReadRepository == null || context == null || context.battingTeamId() == null || game == null || game.getId() == null) {
            return new ScoringPlayResolution(null, 0);
        }
        try {
            List<GameEventReadRepository.GameEventRow> recentEvents = gameEventReadRepository.findRecentByGameId(game.getId(), 30);
            ScoringPlayDetail detail = ScoringPlayDetailExtractor.extract(
                    recentEvents,
                    context
            ).orElse(null);
            return new ScoringPlayResolution(detail, ScoringPlayDetailExtractor.runScoredEventCount(recentEvents));
        } catch (RuntimeException exception) {
            log.debug(
                    "[LiveGameSync] scoring play detail unavailable game={} reason={}",
                    game.getPublicGameId(),
                    exception.getMessage()
            );
            return new ScoringPlayResolution(null, 0);
        }
    }

    private ScoringPlayContext scoringPlayContext(Game game, GameState before, GameState after, String eventTeamId) {
        if (game == null || before == null || after == null || eventTeamId == null) {
            return null;
        }
        return new ScoringPlayContext(
                before.currentBatterName(),
                before.inning() == null ? after.inning() : before.inning(),
                before.inningHalf() == null ? after.inningHalf() : before.inningHalf(),
                before.runnerOnFirst(),
                before.runnerOnSecond(),
                before.runnerOnThird(),
                eventTeamId,
                teamShortName(game, eventTeamId),
                game.getAwayTeam().getTeamCode(),
                game.getHomeTeam().getTeamCode(),
                before.awayScore(),
                before.homeScore(),
                after.awayScore(),
                after.homeScore(),
                teamShortName(game, game.getAwayTeam().getTeamCode()),
                teamShortName(game, game.getHomeTeam().getTeamCode())
        );
    }

    private OnBasePlayContext onBasePlayContext(Game game, GameState before, GameState after, String eventTeamId) {
        if (game == null || before == null || after == null || eventTeamId == null || !hasText(before.currentBatterName())) {
            return null;
        }
        Integer reachedBase = reachedBaseByRunnerName(before.currentBatterName(), after);
        if (reachedBase == null) {
            return null;
        }
        return new OnBasePlayContext(
                before.currentBatterName(),
                reachedBase,
                before.inning() == null ? after.inning() : before.inning(),
                before.inningHalf() == null ? after.inningHalf() : before.inningHalf(),
                eventTeamId,
                teamShortName(game, eventTeamId),
                game.getAwayTeam().getTeamCode(),
                game.getHomeTeam().getTeamCode(),
                after.awayScore(),
                after.homeScore(),
                teamShortName(game, game.getAwayTeam().getTeamCode()),
                teamShortName(game, game.getHomeTeam().getTeamCode())
        );
    }

    private OnBaseDetailResolution onBaseDetail(Game game, OnBasePlayContext context) {
        Instant startedAt = Instant.now(applicationClock);
        OnBasePlayDetail fallback = snapshotOnBaseDetail(context);
        if (gameEventReadRepository == null || game == null || game.getId() == null || context == null) {
            return new OnBaseDetailResolution(fallback, "snapshotDiff", elapsedMillis(startedAt));
        }
        try {
            OnBasePlayDetail officialDetail = CompletableFuture
                    .supplyAsync(() -> OnBasePlayDetailExtractor.extract(
                            gameEventReadRepository.findRecentByGameId(game.getId(), 20),
                            context
                    ).orElse(null))
                    .get(detailExtractionTimeoutMillis(), TimeUnit.MILLISECONDS);
            long durationMs = elapsedMillis(startedAt);
            if (officialDetail != null) {
                return new OnBaseDetailResolution(officialDetail, "officialText", durationMs);
            }
            return new OnBaseDetailResolution(fallback, "snapshotDiff", durationMs);
        } catch (TimeoutException exception) {
            return new OnBaseDetailResolution(fallback, "snapshotDiff", elapsedMillis(startedAt));
        } catch (Exception exception) {
            log.debug(
                    "[LiveGameSync] on-base play detail unavailable game={} reason={}",
                    game.getPublicGameId(),
                    exception.getMessage()
            );
            return new OnBaseDetailResolution(fallback, "snapshotDiff", elapsedMillis(startedAt));
        }
    }

    private OnBasePlayDetail snapshotOnBaseDetail(OnBasePlayContext context) {
        if (context == null) {
            return null;
        }
        return new OnBasePlayDetail(
                context.previousBatterName(),
                null,
                context.reachedBase(),
                context.inning(),
                context.inningHalf(),
                context.battingTeamId(),
                context.battingTeamName(),
                context.awayScore(),
                context.homeScore(),
                context.awayTeamName(),
                context.homeTeamName(),
                "snapshotDiff"
        );
    }

    private Integer reachedBaseByRunnerName(String runnerName, GameState after) {
        String cleaned = cleanText(runnerName);
        if (cleaned == null || after == null) {
            return null;
        }
        if (cleaned.equals(cleanText(after.thirdBaseRunnerName()))) {
            return 3;
        }
        if (cleaned.equals(cleanText(after.secondBaseRunnerName()))) {
            return 2;
        }
        if (cleaned.equals(cleanText(after.firstBaseRunnerName()))) {
            return 1;
        }
        return null;
    }

    private long detailExtractionTimeoutMillis() {
        Duration timeout = properties == null ? null : properties.getDetailExtractionTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return 100;
        }
        return Math.max(1, timeout.toMillis());
    }

    private long elapsedMillis(Instant startedAt) {
        return Math.max(0, Duration.between(startedAt, Instant.now(applicationClock)).toMillis());
    }

    private void addScoringPlayPayload(NotificationEventDraft draft, ScoringPlayDetail detail) {
        if (detail == null) {
            return;
        }
        draft.payload().put("scoringBatterName", detail.batterName());
        draft.payload().put("scoringResultText", detail.resultText());
        draft.payload().put("scoringHitBaseCount", detail.hitBaseCount());
        draft.payload().put("scoringRunsScored", detail.runsScored());
        draft.payload().put("scoringRbi", detail.rbi());
        draft.payload().put("scoringInning", detail.inning());
        draft.payload().put("scoringInningHalf", detail.inningHalf());
        draft.payload().put("scoringBattingTeamId", detail.battingTeamId());
        draft.payload().put("scoringBattingTeamName", detail.battingTeamName());
        draft.payload().put("scoringAwayScoreAfter", detail.awayScoreAfter());
        draft.payload().put("scoringHomeScoreAfter", detail.homeScoreAfter());
        draft.payload().put("scoringSelectedEventType", detail.selectedEventType());
        draft.payload().put("scoringSelectedEventText", detail.selectedEventText());
        draft.payload().put("scoringRunScoredEventCount", detail.runScoredEventCount());
    }

    private void addOnBaseDetailPayload(NotificationEventDraft draft, OnBaseDetailResolution resolution) {
        if (resolution == null || resolution.detail() == null) {
            return;
        }
        OnBasePlayDetail detail = resolution.detail();
        draft.payload().put("onBaseBatterName", detail.batterName());
        draft.payload().put("onBaseResultText", detail.resultText());
        draft.payload().put("onBaseReachedBase", detail.reachedBase());
        draft.payload().put("onBaseInning", detail.inning());
        draft.payload().put("onBaseInningHalf", detail.inningHalf());
        draft.payload().put("onBaseBattingTeamId", detail.battingTeamId());
        draft.payload().put("onBaseBattingTeamName", detail.battingTeamName());
        draft.payload().put("onBaseDetailSource", resolution.detailSource());
        draft.payload().put("onBaseDetailExtractionDurationMs", resolution.durationMs());
    }

    private NotificationEventDraft draft(Game game, String eventType, String eventKey, String title, String body) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("gameId", game.getId().toString());
        payload.put("publicGameId", game.getPublicGameId());
        payload.put("eventType", eventType);
        payload.put("homeTeamId", game.getHomeTeam().getTeamCode());
        payload.put("homeTeamName", game.getHomeTeam().getName());
        payload.put("awayTeamId", game.getAwayTeam().getTeamCode());
        payload.put("awayTeamName", game.getAwayTeam().getName());
        payload.put("homeScore", game.getHomeScore());
        payload.put("awayScore", game.getAwayScore());
        payload.put("scheduledAt", game.getScheduledAt() == null ? null : game.getScheduledAt().toString());
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
            String batterName,
            String pitcherName,
            String result,
            Integer runCount,
            String eventTeamId
    ) {
        NotificationEventDraft draft = draft(game, eventType, eventKey, title, body);
        draft.payload().put("batterName", batterName);
        draft.payload().put("pitcherName", pitcherName);
        draft.payload().put("result", result);
        draft.payload().put("runCount", runCount);
        draft.payload().put(NotificationEventService.PAYLOAD_EVENT_TEAM_ID, eventTeamId);
        return draft;
    }

    private String scoringTeamId(Game game, GameState before, GameState after) {
        int awayDelta = Math.max(0, nullSafe(after.awayScore()) - nullSafe(before.awayScore()));
        int homeDelta = Math.max(0, nullSafe(after.homeScore()) - nullSafe(before.homeScore()));
        if (awayDelta > 0 && homeDelta == 0) {
            return game.getAwayTeam().getTeamCode();
        }
        if (homeDelta > 0 && awayDelta == 0) {
            return game.getHomeTeam().getTeamCode();
        }
        return null;
    }

    private String battingTeamId(Game game) {
        String inningState = game.getInningState();
        if (inningState == null || inningState.isBlank()) {
            return null;
        }
        String normalized = inningState.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("top") || normalized.contains("초")) {
            return game.getAwayTeam().getTeamCode();
        }
        if (normalized.startsWith("bot") || normalized.startsWith("bottom") || normalized.contains("말")) {
            return game.getHomeTeam().getTeamCode();
        }
        return null;
    }

    private boolean isLiveLike(GameStatus status) {
        return status == GameStatus.LIVE || status == GameStatus.SUSPENDED;
    }

    private boolean isCancellationTransition(GameStatus before, GameStatus after) {
        return before != after
                && isCancellationTarget(after)
                && (before == GameStatus.SCHEDULED || before == GameStatus.UNKNOWN || isLiveLike(before));
    }

    private boolean isCancellationTarget(GameStatus status) {
        return status == GameStatus.CANCELLED || status == GameStatus.POSTPONED;
    }

    private boolean isScheduleCancellationTransition(GameStatus before, GameStatus after) {
        return before != after
                && isScheduleCancellationTarget(after)
                && (before == GameStatus.SCHEDULED || before == GameStatus.UNKNOWN || isLiveLike(before));
    }

    private boolean isScheduleCancellationTarget(GameStatus status) {
        return status == GameStatus.CANCELLED || status == GameStatus.POSTPONED;
    }

    private boolean isInterruptionTransition(GameState before, GameState after) {
        return before.status() != after.status()
                && (isLiveLike(before.status()) || isWeakFinal(before))
                && isInterrupted(after.status())
                && !isInterrupted(before.status());
    }

    private boolean isWeakFinal(GameState state) {
        return state.status() == GameStatus.FINAL
                && (state.finalConfirmedAt() == null || !hasReliableFinalStatusReason(state.statusReason()));
    }

    private boolean isResumeTransition(GameStatus before, GameStatus after) {
        return isInterrupted(before) && after == GameStatus.LIVE;
    }

    private boolean isInterrupted(GameStatus status) {
        return status == GameStatus.SUSPENDED;
    }

    private String cancellationBody(Game game, GameStatus cancelledStatus) {
        String matchup = "%s vs %s".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        String result = switch (cancelledStatus) {
            case POSTPONED -> "순연되었습니다.";
            case SUSPENDED -> "중단되었습니다.";
            default -> game.getCancelReason() == GameCancelReason.RAIN ? "우천취소되었습니다." : "취소되었습니다.";
        };
        String context = cancellationContext(game);
        return "%s 경기가 %s%s".formatted(matchup, result, context);
    }

    private String cancellationContext(Game game) {
        List<String> parts = new ArrayList<>();
        if (game.getStadium() != null && !game.getStadium().isBlank()) {
            parts.add(game.getStadium());
        }
        if (game.getScheduledAt() != null) {
            parts.add(game.getScheduledAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(9)).format(CANCELLED_GAME_TIME_FORMATTER));
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }

    private boolean inningChanged(GameState before, GameState after) {
        return before.inning() != null
                && before.inningHalf() != null
                && after.inning() != null
                && after.inningHalf() != null
                && (!java.util.Objects.equals(before.inning(), after.inning())
                || !java.util.Objects.equals(normalizeHalf(before.inningHalf()), normalizeHalf(after.inningHalf())));
    }

    private String leadChangeEventTeamId(Game game, GameState before, GameState after) {
        if (!isLiveLike(after.status()) || before.awayScore() == null || before.homeScore() == null || after.awayScore() == null || after.homeScore() == null) {
            return null;
        }
        if (!scoreIncreased(before, after)) {
            return null;
        }
        String previousLeader = leadingTeamId(game, before.awayScore(), before.homeScore());
        String currentLeader = leadingTeamId(game, after.awayScore(), after.homeScore());
        if (currentLeader != null && !currentLeader.equals(previousLeader)) {
            return currentLeader;
        }
        if (previousLeader != null && currentLeader == null) {
            return scoringTeamId(game, before, after);
        }
        return null;
    }

    private String onBaseBody(Game game, String batterName, String result) {
        String teamName = teamShortName(game, battingTeamId(game));
        if (batterName != null && !batterName.isBlank()) {
            return "%s: %s 출루".formatted(teamName, batterName.trim());
        }
        return "%s 출루".formatted(teamName);
    }

    private NotificationText scoringFallbackText(ScoringPlayContext context) {
        if (context == null) {
            return new NotificationText("득점", "득점 상황 발생");
        }
        return new NotificationText(
                "%s 득점".formatted(context.battingTeamName()),
                scoringFallbackBody(context)
        );
    }

    private NotificationText leadChangeFallbackText(
            ScoringPlayContext context,
            Integer previousAwayScore,
            Integer previousHomeScore
    ) {
        if (context == null) {
            return new NotificationText("리드 변경", "득점 상황 발생");
        }
        return new NotificationText(
                leadChangeFallbackTitle(context, previousAwayScore, previousHomeScore),
                scoringFallbackBody(context)
        );
    }

    private String leadChangeFallbackTitle(
            ScoringPlayContext context,
            Integer previousAwayScore,
            Integer previousHomeScore
    ) {
        int away = nullSafe(context.awayScoreAfter());
        int home = nullSafe(context.homeScoreAfter());
        if (away == home) {
            return "동점";
        }
        String currentLeaderTeamId = away > home ? context.awayTeamId() : context.homeTeamId();
        if (currentLeaderTeamId != null
                && context.battingTeamId() != null
                && currentLeaderTeamId.equalsIgnoreCase(context.battingTeamId())) {
            int previousAway = nullSafe(previousAwayScore);
            int previousHome = nullSafe(previousHomeScore);
            if ((previousAway <= previousHome && away > home) || (previousHome <= previousAway && home > away)) {
                return "%s 역전".formatted(context.battingTeamName());
            }
        }
        return "리드 변경";
    }

    private String scoringFallbackBody(ScoringPlayContext context) {
        String playText = "%s %s".formatted(context.battingTeamName(), runsText(context.scoreDelta()));
        String inningText = scoringInningText(context.inning(), context.inningHalf());
        if (hasText(inningText)) {
            return "%s %s · %s".formatted(inningText, playText, scoringScoreText(context));
        }
        return "%s · %s".formatted(playText, scoringScoreText(context));
    }

    private String scoringInningText(Integer inning, String inningHalf) {
        if (inning == null) {
            return null;
        }
        String half = switch (normalizeHalf(inningHalf)) {
            case "top" -> "초";
            case "bottom" -> "말";
            default -> "";
        };
        return "%d회%s".formatted(inning, half);
    }

    private String runsText(Integer runsScored) {
        return "%d득점".formatted(Math.max(1, nullSafe(runsScored)));
    }

    private String scoringScoreText(ScoringPlayContext context) {
        if (context.battingTeamId() != null && context.battingTeamId().equalsIgnoreCase(context.awayTeamId())) {
            return "%s %d-%d %s".formatted(
                    context.awayTeamName(),
                    nullSafe(context.awayScoreAfter()),
                    nullSafe(context.homeScoreAfter()),
                    context.homeTeamName()
            );
        }
        if (context.battingTeamId() != null && context.battingTeamId().equalsIgnoreCase(context.homeTeamId())) {
            return "%s %d-%d %s".formatted(
                    context.homeTeamName(),
                    nullSafe(context.homeScoreAfter()),
                    nullSafe(context.awayScoreAfter()),
                    context.awayTeamName()
            );
        }
        return "%s %d-%d %s".formatted(
                context.awayTeamName(),
                nullSafe(context.awayScoreAfter()),
                nullSafe(context.homeScoreAfter()),
                context.homeTeamName()
        );
    }

    private String leadingTeamId(Game game, Integer awayScore, Integer homeScore) {
        int away = nullSafe(awayScore);
        int home = nullSafe(homeScore);
        if (away > home) {
            return game.getAwayTeam().getTeamCode();
        }
        if (home > away) {
            return game.getHomeTeam().getTeamCode();
        }
        return null;
    }

    private String winningTeamId(Game game) {
        return game.getAwayScore() == null || game.getHomeScore() == null
                ? null
                : leadingTeamId(game, game.getAwayScore(), game.getHomeScore());
    }

    private String losingTeamId(Game game) {
        String winner = winningTeamId(game);
        if (winner == null) {
            return null;
        }
        if (winner.equals(game.getAwayTeam().getTeamCode())) {
            return game.getHomeTeam().getTeamCode();
        }
        return game.getAwayTeam().getTeamCode();
    }

    private String teamShortName(Game game, String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return "KBO";
        }
        String normalized = teamId.trim().toLowerCase(java.util.Locale.ROOT);
        if (game.getAwayTeam().getTeamCode().equalsIgnoreCase(teamId)) {
            return displayTeamName(game.getAwayTeam().getTeamCode(), game.getAwayTeam().getShortName());
        }
        if (game.getHomeTeam().getTeamCode().equalsIgnoreCase(teamId)) {
            return displayTeamName(game.getHomeTeam().getTeamCode(), game.getHomeTeam().getShortName());
        }
        return TEAM_DISPLAY_NAMES.getOrDefault(normalized, teamId.trim());
    }

    private String displayTeamName(String teamCode, String fallback) {
        String normalized = teamCode == null ? null : teamCode.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized != null && TEAM_DISPLAY_NAMES.containsKey(normalized)) {
            return TEAM_DISPLAY_NAMES.get(normalized);
        }
        return fallback == null || fallback.isBlank() ? "KBO" : fallback.trim();
    }

    private String inningDisplayLabel(GameState state) {
        if (state.inningLabel() != null && !state.inningLabel().isBlank()) {
            return localizedInningLabel(state.inningLabel());
        }
        if (state.inning() == null) {
            return "이닝 교체";
        }
        String half = switch (normalizeHalf(state.inningHalf())) {
            case "top" -> "초";
            case "bottom" -> "말";
            default -> "";
        };
        return "%d회 %s".formatted(state.inning(), half).trim();
    }

    private String localizedInningLabel(String label) {
        if (label == null || label.isBlank()) {
            return label;
        }
        String trimmed = label.trim();
        java.util.regex.Matcher english = java.util.regex.Pattern
                .compile("(?i)^(top|bottom|bot)\\s*(\\d+)$")
                .matcher(trimmed);
        if (english.matches()) {
            String half = normalizeHalf(english.group(1));
            return "%s회 %s".formatted(english.group(2), "top".equals(half) ? "초" : "말");
        }
        java.util.regex.Matcher korean = java.util.regex.Pattern
                .compile("^(\\d+)회\\s*([초말])$")
                .matcher(trimmed);
        if (korean.matches()) {
            return "%s회 %s".formatted(korean.group(1), korean.group(2));
        }
        return trimmed;
    }

    private String normalizeHalf(String half) {
        if (half == null || half.isBlank()) {
            return null;
        }
        String normalized = half.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.startsWith("top") || normalized.equals("초")) {
            return "top";
        }
        if (normalized.startsWith("bot") || normalized.startsWith("bottom") || normalized.equals("말")) {
            return "bottom";
        }
        return normalized;
    }

    private GameSnapshot latestSnapshot(Game game) {
        if (gameSnapshotRepository == null || game == null || game.getId() == null) {
            return null;
        }
        return gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId()).orElse(null);
    }

    private String liveEventBody(String batterName, String pitcherName, String result, Integer runCount) {
        String playText;

        if (batterName != null && !batterName.isBlank()
                && pitcherName != null && !pitcherName.isBlank()) {
            playText = "%s, %s 상대 %s".formatted(batterName.trim(), pitcherName.trim(), result);
        } else if (batterName != null && !batterName.isBlank()) {
            playText = "%s %s".formatted(batterName.trim(), result);
        } else if (runCount == null) {
            playText = "출루 상황 발생";
        } else {
            playText = "득점 상황 발생";
        }

        return runCount == null ? playText : playText + "\n" + runCount + "득점";
    }

    private String liveEventResult(Game game, String fallback) {
        String text = String.join(" ",
                game.getStatusReason() == null ? "" : game.getStatusReason(),
                game.getInningState() == null ? "" : game.getInningState()
        );
        for (String method : List.of(
                "몸에 맞는 공",
                "낫아웃 출루",
                "야수선택",
                "고의사구",
                "사구",
                "볼넷",
                "홈런",
                "2루타",
                "3루타",
                "적시타",
                "안타",
                "실책"
        )) {
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim().replaceAll("\\s+", " ");
    }

    private String normalizedInterruptionReason(Game game) {
        String reason = game.getStatusReason();
        if (reason == null || reason.isBlank()) {
            reason = game.getRawCancelText();
        }
        if (reason == null || reason.isBlank()) {
            return "interrupted";
        }
        return reason.trim().replaceAll("\\s+", "_").toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isRainInterruption(String normalizedReason) {
        return normalizedReason != null
                && (normalizedReason.contains("우천")
                || normalizedReason.contains("강우")
                || normalizedReason.contains("rain"));
    }

    private boolean changedText(String before, String after) {
        return after != null && !after.isBlank() && !java.util.Objects.equals(before, after);
    }

    private boolean scoreIncreased(GameState before, GameState after) {
        return nullSafe(after.awayScore()) > nullSafe(before.awayScore())
                || nullSafe(after.homeScore()) > nullSafe(before.homeScore());
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
            OffsetDateTime finalConfirmedAt,
            String statusReason,
            Integer homeScore,
            Integer awayScore,
            String homeStartingPitcherName,
            String awayStartingPitcherName,
            String lineupHash,
            Integer inning,
            String inningHalf,
            String inningLabel,
            Integer outs,
            boolean runnerOnFirst,
            boolean runnerOnSecond,
            boolean runnerOnThird,
            String firstBaseRunnerName,
            String secondBaseRunnerName,
            String thirdBaseRunnerName,
            String currentPitcherName,
            String currentBatterName
    ) {
        static GameState from(Game game, GameSnapshot snapshot) {
            return new GameState(
                    game.getStatus(),
                    game.isCancelled(),
                    game.isPostponed(),
                    game.getFinalConfirmedAt(),
                    clean(game.getStatusReason()),
                    game.getHomeScore(),
                    game.getAwayScore(),
                    game.getHomeStartingPitcherName(),
                    game.getAwayStartingPitcherName(),
                    game.getLineupData() == null ? null : String.valueOf(game.getLineupData().hashCode()),
                    snapshot == null ? null : snapshot.getInning(),
                    clean(snapshot == null ? null : snapshot.getInningHalf()),
                    clean(snapshot == null ? null : snapshot.getInningLabel()),
                    snapshot == null ? null : snapshot.getOuts(),
                    snapshot != null && snapshot.isRunnerOnFirst(),
                    snapshot != null && snapshot.isRunnerOnSecond(),
                    snapshot != null && snapshot.isRunnerOnThird(),
                    clean(snapshot == null ? null : snapshot.getFirstBaseRunnerName()),
                    clean(snapshot == null ? null : snapshot.getSecondBaseRunnerName()),
                    clean(snapshot == null ? null : snapshot.getThirdBaseRunnerName()),
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

    private record OnBaseDetailResolution(
            OnBasePlayDetail detail,
            String detailSource,
            long durationMs
    ) {
    }

    private record ScoringPlayResolution(
            ScoringPlayDetail detail,
            int runScoredEventCount
    ) {
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
