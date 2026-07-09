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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LiveGameSyncService {

    private static final Logger log = LoggerFactory.getLogger(LiveGameSyncService.class);
    private static final int DEFAULT_SNAPSHOT_RECOVERY_PAIR_LIMIT = 20;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter CANCELLED_GAME_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final Pattern RESUME_TIME_PATTERN = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)");
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
    private final LiveActivityPushToStartTokenService liveActivityPushToStartTokenService;
    private final TeamRankService teamRankService;
    private final LiveSyncProperties properties;
    private final Clock applicationClock;
    private final DateSyncLockService dateSyncLockService;
    private final Map<UUID, Instant> nextRefreshAtByGameId = new ConcurrentHashMap<>();
    private final Set<UUID> suspiciousStallLoggedGameIds = ConcurrentHashMap.newKeySet();

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
                null,
                teamRankService,
                properties,
                applicationClock,
                new DateSyncLockService()
        );
    }

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
        this(
                gameRepository,
                gameSnapshotRepository,
                gameEventReadRepository,
                gameDetailImportService,
                kboScheduleImportService,
                notificationEventService,
                null,
                teamRankService,
                properties,
                applicationClock,
                new DateSyncLockService()
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
            LiveActivityPushToStartTokenService liveActivityPushToStartTokenService,
            TeamRankService teamRankService,
            LiveSyncProperties properties,
            Clock applicationClock,
            DateSyncLockService dateSyncLockService
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.gameEventReadRepository = gameEventReadRepository;
        this.gameDetailImportService = gameDetailImportService;
        this.kboScheduleImportService = kboScheduleImportService;
        this.notificationEventService = notificationEventService;
        this.liveActivityPushToStartTokenService = liveActivityPushToStartTokenService;
        this.teamRankService = teamRankService;
        this.properties = properties;
        this.applicationClock = applicationClock;
        this.dateSyncLockService = dateSyncLockService == null ? new DateSyncLockService() : dateSyncLockService;
    }

    public LocalDate todayKst() {
        return LocalDate.now(applicationClock.withZone(KST));
    }

    public LiveSyncSummary syncToday() {
        return sync(todayKst(), false);
    }

    public LiveSyncSummary sync(LocalDate date, boolean force) {
        LocalDate targetDate = date == null ? todayKst() : date;
        Instant startedAt = Instant.now(applicationClock);
        DateSyncLockService.SyncLock lock = dateSyncLockService.tryLock(targetDate);
        try {
            if (!lock.acquired()) {
                if (lock.heldDurationMs() == -1L) {
                    log.warn(
                            "[LiveGameSync] lock unavailable date={} reason=held_by_external_or_pooled_session heldDurationMs={}",
                            targetDate,
                            lock.heldDurationMs()
                    );
                }
                log.info(
                        "[LiveGameSync] skipped date={} reason=sync_already_in_progress durationMs={} heldDurationMs={}",
                        targetDate,
                        elapsedMillis(startedAt),
                        lock.heldDurationMs()
                );
                return new LiveSyncSummary(targetDate, 0, 0, 0, 0, 0, 1, 0, List.of(), List.of(), List.of("sync already in progress"));
            }
            log.info("[LiveGameSync] start date={} force={}", targetDate, force);
            return syncWithLock(targetDate, force);
        } finally {
            if (lock.acquired()) {
                lock.close();
                log.info(
                        "[LiveGameSync] end date={} durationMs={} inProgressReleased=true",
                        targetDate,
                        elapsedMillis(startedAt)
                );
            }
        }
    }

    private LiveSyncSummary syncWithLock(LocalDate targetDate, boolean force) {
        log.debug("[LiveGameSync] started date={}", targetDate);
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
            logSuspiciousScheduledStall(game);
            if (attempted) {
                candidates.add(game);
            }
        }
        log.debug("[LiveGameSync] candidate count={}", candidates.size());

        int updatedCount = 0;
        int eventCreatedCount = 0;
        int notificationSentCount = 0;
        int notificationSkippedCount = 0;
        int failedCount = 0;
        List<String> updatedGames = new ArrayList<>();
        List<String> events = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Set<String> handledEventKeys = new HashSet<>();

        for (Game game : games) {
            GameState before = beforeScheduleStates.get(game.getPublicGameId());
            if (before == null) {
                continue;
            }
            GameState afterScheduleState = GameState.from(game, latestSnapshot(game));
            if (downgradeUnreliableLiveIfNeeded(game, afterScheduleState, "schedule-refresh")) {
                afterScheduleState = GameState.from(game, latestSnapshot(game));
                beforeScheduleStates.put(game.getPublicGameId(), afterScheduleState);
            }
            NotificationEventDraft draft;
            boolean started = false;
            boolean interrupted = false;
            if (shouldCreateGameStartDraft(before, afterScheduleState, game)) {
                log.info(
                        "[LiveGameSync] schedule-level GAME_START detected game={} providerGameId={} scheduledAt={} previousStatus={} currentStatus={}",
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getScheduledAt(),
                        before.status(),
                        game.getStatus()
                );
                draft = startedDraft(game);
                started = true;
                logGameStartTiming(game, draft, "schedule-refresh");
            } else if (isCancellationTransition(before.status(), afterScheduleState.status())) {
                log.info(
                        "[LiveGameSync] schedule-level cancellation detected game={} previousStatus={} scheduleStatus={} cancelReason={} rawCancelText={}",
                        game.getPublicGameId(),
                        before.status(),
                        afterScheduleState.status(),
                        game.getCancelReason(),
                        game.getRawCancelText()
                );
                draft = cancelledDraft(game, afterScheduleState.status());
            } else if (isInterruptionTransition(before, afterScheduleState)) {
                log.info(
                        "[LiveGameSync] schedule-level interruption detected game={} previousStatus={} scheduleStatus={} statusReason={}",
                        game.getPublicGameId(),
                        before.status(),
                        afterScheduleState.status(),
                        game.getStatusReason()
                );
                draft = interruptionDraft(game, before, afterScheduleState);
                interrupted = true;
            } else {
                continue;
            }
            EventDeliveryResult delivery = notificationEventService.createAndDeliverAsync(game, draft);
            updatedCount++;
            updatedGames.add(game.getPublicGameId());
            if (delivery.eventCreated()) {
                eventCreatedCount++;
                events.add(delivery.eventKey());
                handledEventKeys.add(delivery.eventKey());
                if (started) {
                    log.info(
                            "[LiveGameSync] GAME_START event created_at={} game={} providerGameId={} scheduledAt={} eventKey={} source=schedule-refresh",
                            Instant.now(applicationClock),
                            game.getPublicGameId(),
                            game.getProviderGameId(),
                            game.getScheduledAt(),
                            delivery.eventKey()
                    );
                    deliverLiveActivityStart(game, draft);
                } else {
                    log.info(
                            "[LiveGameSync] schedule-level event created game={} eventType={} eventKey={}",
                            game.getPublicGameId(),
                            draft.eventType(),
                            delivery.eventKey()
                    );
                }
            }
            notificationSentCount += delivery.sentCount();
            notificationSkippedCount += delivery.skippedCount();
            if (started || interrupted) {
                beforeScheduleStates.put(game.getPublicGameId(), GameState.from(game, latestSnapshot(game)));
            } else {
                log.info(
                        "[LiveGameSync] cancellation notification sent/skipped game={} eventKey={} sent={} skipped={} created={}",
                        game.getPublicGameId(),
                        delivery.eventKey(),
                        delivery.sentCount(),
                        delivery.skippedCount(),
                        delivery.eventCreated()
                );
            }
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
                GameState afterState = GameState.from(after, latestSnapshot(after));
                if (downgradeUnreliableLiveIfNeeded(after, afterState, "detail-import")) {
                    afterState = GameState.from(after, latestSnapshot(after));
                }
                if (becameFinalOrFinalConfirmed(before, after, finalConfirmed)) {
                    teamRankService.refreshSeasonRankingsSafely(after.getGameDate().getYear());
                }

                List<NotificationEventDraft> drafts = detectChanges(
                        before,
                        afterState,
                        after
                );
                if (!drafts.isEmpty()) {
                    updatedCount++;
                    updatedGames.add(after.getPublicGameId());
                }
                for (NotificationEventDraft draft : drafts) {
                    if (shouldSkipFinalMutableNotification(after, draft)) {
                        logFinalMutableNotificationSkipped(after, draft);
                        continue;
                    }
                    log.info(
                            "[Notifications] notification built at={} eventType={} eventKey={} publicGameId={}",
                            Instant.now(applicationClock),
                            draft.eventType(),
                            draft.eventKey(),
                            after.getPublicGameId()
                    );
                    EventDeliveryResult delivery = notificationEventService.createAndDeliverAsync(after, draft);
                    handledEventKeys.add(draft.eventKey());
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
                    if (delivery.eventCreated() && NotificationEventService.EVENT_GAME_START.equals(draft.eventType())) {
                        log.info(
                                "[LiveGameSync] GAME_START event created_at={} game={} providerGameId={} scheduledAt={} eventKey={} source=detail-import",
                                Instant.now(applicationClock),
                                after.getPublicGameId(),
                                after.getProviderGameId(),
                                after.getScheduledAt(),
                                delivery.eventKey()
                        );
                        logGameStartTiming(after, draft, "detail-import");
                        deliverLiveActivityStart(after, draft);
                    }
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
                SnapshotRecoveryResult recovery = recoverSnapshotNotifications(after, handledEventKeys);
                if (recovery.eventCreatedCount() > 0) {
                    updatedCount++;
                    updatedGames.add(after.getPublicGameId());
                    eventCreatedCount += recovery.eventCreatedCount();
                    events.addAll(recovery.eventKeys());
                    notificationSentCount += recovery.sentCount();
                    notificationSkippedCount += recovery.skippedCount();
                }
                nextRefreshAtByGameId.put(after.getId(), Instant.now(applicationClock).plus(ttlFor(after)));
            } catch (RuntimeException exception) {
                failedCount++;
                errors.add(candidate.getPublicGameId() + ": " + exception.getMessage());
                log.warn("[LiveGameSync] failed game id={} reason={}", candidate.getPublicGameId(), exception.getMessage());
                nextRefreshAtByGameId.put(candidate.getId(), Instant.now(applicationClock).plus(Duration.ofMinutes(1)));
            }
        }

        boolean notable = updatedCount > 0 || eventCreatedCount > 0 || notificationSentCount > 0 || failedCount > 0;
        if (notable) {
            log.info(
                    "[LiveGameSync] summary date={} scanned={} candidates={} updated={} events={} sent={} skipped={} failed={}",
                    targetDate,
                    games.size(),
                    candidates.size(),
                    updatedCount,
                    eventCreatedCount,
                    notificationSentCount,
                    notificationSkippedCount,
                    failedCount
            );
        } else {
            log.debug(
                    "[LiveGameSync] summary date={} scanned={} candidates={} updated={} events={} sent={} skipped={} failed={}",
                    targetDate,
                    games.size(),
                    candidates.size(),
                    updatedCount,
                    eventCreatedCount,
                    notificationSentCount,
                    notificationSkippedCount,
                    failedCount
            );
        }
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

    private SnapshotRecoveryResult recoverSnapshotNotifications(Game game, Set<String> handledEventKeys) {
        List<NotificationEventDraft> candidates = snapshotRecoveryDrafts(game, DEFAULT_SNAPSHOT_RECOVERY_PAIR_LIMIT);
        if (candidates.isEmpty()) {
            return SnapshotRecoveryResult.empty();
        }
        int eventCreatedCount = 0;
        int sentCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        List<String> eventKeys = new ArrayList<>();
        for (NotificationEventDraft draft : candidates) {
            if (shouldSkipFinalMutableNotification(game, draft)) {
                logFinalMutableNotificationSkipped(game, draft);
                continue;
            }
            if (handledEventKeys.contains(draft.eventKey()) || notificationEventService.eventExists(draft)) {
                logSnapshotRecoveryDecision(game, null, null, draft, "duplicate_event_key", "duplicate_event_key");
                continue;
            }
            logSnapshotRecoveryDecision(game, null, null, draft, "candidate", null);
            EventDeliveryResult delivery = notificationEventService.createAndDeliverAsync(game, draft);
            handledEventKeys.add(draft.eventKey());
            if (!delivery.eventCreated()) {
                logSnapshotRecoveryDecision(game, null, null, draft, "duplicate_event_key", "duplicate_event_key");
                continue;
            }
            eventCreatedCount++;
            eventKeys.add(delivery.eventKey());
            sentCount += delivery.sentCount();
            skippedCount += delivery.skippedCount();
            failedCount += delivery.failedCount();
            String decision = delivery.failedCount() > 0
                    ? "failed"
                    : delivery.sentCount() > 0
                    ? "sent"
                    : delivery.skippedCount() > 0
                    ? "no_target_devices"
                    : "created";
            logSnapshotRecoveryDecision(game, null, null, draft, decision, "created");
        }
        return new SnapshotRecoveryResult(eventCreatedCount, sentCount, skippedCount, failedCount, eventKeys);
    }

    public NotificationRecoveryDiagnosis diagnoseNotificationRecovery(String publicGameId) {
        if (publicGameId == null || publicGameId.isBlank()) {
            throw new com.kbo.crawlerapi.api.InvalidParameterException("publicGameId is required");
        }
        Game game = gameRepository.findByPublicGameId(publicGameId.trim())
                .orElseThrow(() -> new com.kbo.crawlerapi.api.ResourceNotFoundException("game not found: " + publicGameId));
        List<GameSnapshot> snapshots = recentReplaySnapshots(game, DEFAULT_SNAPSHOT_RECOVERY_PAIR_LIMIT);
        List<NotificationEventDraft> candidates = snapshotRecoveryDrafts(game, DEFAULT_SNAPSHOT_RECOVERY_PAIR_LIMIT);
        long storedCount = notificationEventService.countByGameId(game.getId());
        long missingCount = candidates.stream()
                .filter(candidate -> !notificationEventService.eventExists(candidate))
                .count();
        return new NotificationRecoveryDiagnosis(
                game.getId().toString(),
                game.getPublicGameId(),
                snapshots.size(),
                candidates.size(),
                storedCount,
                missingCount
        );
    }

    private List<NotificationEventDraft> snapshotRecoveryDrafts(Game game, int pairLimit) {
        List<GameSnapshot> snapshots = recentReplaySnapshots(game, pairLimit);
        if (snapshots.size() < 2) {
            return terminalRecoveryDrafts(game, snapshots);
        }
        Map<String, NotificationEventDraft> draftsByKey = new LinkedHashMap<>();
        for (int index = 1; index < snapshots.size(); index++) {
            GameSnapshot beforeSnapshot = snapshots.get(index - 1);
            GameSnapshot afterSnapshot = snapshots.get(index);
            Game beforeGame = replayGame(game, beforeSnapshot, GameStatus.LIVE);
            Game afterGame = replayGame(game, afterSnapshot, GameStatus.LIVE);
            List<NotificationEventDraft> candidates = detectChanges(
                    GameState.fromReplay(beforeGame, beforeSnapshot, GameStatus.LIVE, null, beforeGame.getStatusReason()),
                    GameState.fromReplay(afterGame, afterSnapshot, GameStatus.LIVE, null, afterGame.getStatusReason()),
                    afterGame
            );
            for (NotificationEventDraft candidate : candidates) {
                draftsByKey.putIfAbsent(candidate.eventKey(), candidate);
                logSnapshotRecoveryDecision(game, beforeSnapshot, afterSnapshot, candidate, "candidate", null);
            }
        }
        for (NotificationEventDraft terminalDraft : terminalRecoveryDrafts(game, snapshots)) {
            draftsByKey.putIfAbsent(terminalDraft.eventKey(), terminalDraft);
        }
        return new ArrayList<>(draftsByKey.values());
    }

    private List<NotificationEventDraft> terminalRecoveryDrafts(Game game, List<GameSnapshot> snapshots) {
        if (game == null) {
            return List.of();
        }
        if (isCancellationTarget(game.getStatus())) {
            return List.of(cancelledDraft(game, game.getStatus()));
        }
        if (game.getStatus() != GameStatus.FINAL || snapshots.isEmpty() || !isStrongFinal(game)) {
            return List.of();
        }
        GameSnapshot lastSnapshot = snapshots.get(snapshots.size() - 1);
        Game beforeGame = replayGame(game, lastSnapshot, GameStatus.LIVE);
        Game finalGame = replayGame(game, lastSnapshot, GameStatus.FINAL);
        return detectChanges(
                GameState.fromReplay(beforeGame, lastSnapshot, GameStatus.LIVE, null, beforeGame.getStatusReason()),
                GameState.fromReplay(finalGame, lastSnapshot, GameStatus.FINAL, finalGame.getFinalConfirmedAt(), finalGame.getStatusReason()),
                finalGame
        );
    }

    private List<GameSnapshot> recentReplaySnapshots(Game game, int pairLimit) {
        if (gameSnapshotRepository == null || game == null || game.getId() == null) {
            return List.of();
        }
        List<GameSnapshot> snapshots = new ArrayList<>(gameSnapshotRepository.findRecentReplaySnapshotsByGameId(
                game.getId(),
                PageRequest.of(0, Math.max(2, pairLimit + 1))
        ));
        Collections.reverse(snapshots);
        return snapshots;
    }

    private void deliverLiveActivityStart(Game game, NotificationEventDraft draft) {
        if (liveActivityPushToStartTokenService == null) {
            log.info(
                    "[LiveActivityStart] APNs skipped publicGameId={} providerGameId={} databaseId={} eventKey={} reason=unsupported_os_or_capability",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    draft.eventKey()
            );
            return;
        }
        LiveActivityPushToStartTokenService.LiveActivityStartDeliveryResult result = liveActivityPushToStartTokenService.deliverStart(game);
        log.info(
                "[LiveActivityStart] APNs result publicGameId={} providerGameId={} databaseId={} eventKey={} sent={} skipped={} failed={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getId(),
                draft.eventKey(),
                result.sentCount(),
                result.skippedCount(),
                result.failedCount()
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
        if (isCancellationTarget(game.getStatus())) {
            return "schedule-cancellation-target";
        }
        if (!shouldRunDetailImport(game)) {
            return "not-live-like";
        }
        return null;
    }

    private void logCandidateDiagnostics(Game game, boolean detailImportAttempted, String skipReason) {
        if (detailImportAttempted) {
            log.info(
                    "[LiveGameSync] candidate diagnostics publicGameId={} providerGameId={} storedStatus={} storedStatusReason={} scheduledAt={} gameDate={} detailImport={} skipReason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getStatus(),
                    game.getStatusReason(),
                    game.getScheduledAt(),
                    game.getGameDate(),
                    "attempted",
                    skipReason
            );
        } else {
            log.debug(
                    "[LiveGameSync] candidate diagnostics publicGameId={} providerGameId={} storedStatus={} storedStatusReason={} scheduledAt={} gameDate={} detailImport={} skipReason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getStatus(),
                    game.getStatusReason(),
                    game.getScheduledAt(),
                    game.getGameDate(),
                    "skipped",
                    skipReason
            );
        }
    }

    private void logSuspiciousScheduledStall(Game game) {
        if (game.getStatus() != GameStatus.SCHEDULED || !hasScheduledStartReached(game)) {
            return;
        }
        GameSnapshot latestSnapshot = latestSnapshot(game);
        long snapshotCount = snapshotCount(game);
        boolean inningLooksStarted = inningAtTopOrBottomOneOrLater(game.getInningState())
                || (latestSnapshot != null && latestSnapshot.getInning() != null && latestSnapshot.getInning() >= 1);
        boolean snapshotStalled = snapshotCount <= 1;
        boolean liveCheckOnly = game.getLiveLastCheckedAt() != null;
        if (!inningLooksStarted && !snapshotStalled && !liveCheckOnly) {
            return;
        }
        if (game.getId() != null && !suspiciousStallLoggedGameIds.add(game.getId())) {
            return;
        }
        log.warn(
                "[LiveGameSync] suspicious scheduled stall publicGameId={} providerGameId={} reason=scheduled_after_start_with_stalled_live_state scheduledAt={} status={} inningState={} snapshotCount={} lastSnapshotAt={} liveLastCheckedAt={} diagnostics=scheduled_after_start,inning_top_or_bottom_1_or_later,snapshot_count_le_1,live_last_checked_only",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getScheduledAt(),
                game.getStatus(),
                game.getInningState(),
                snapshotCount,
                latestSnapshot == null ? null : snapshotObservedAt(latestSnapshot),
                game.getLiveLastCheckedAt()
        );
    }

    private long snapshotCount(Game game) {
        if (gameSnapshotRepository == null || game == null || game.getId() == null) {
            return 0;
        }
        return gameSnapshotRepository.countByGame_Id(game.getId());
    }

    private boolean inningAtTopOrBottomOneOrLater(String inningState) {
        if (inningState == null || inningState.isBlank()) {
            return false;
        }
        String normalized = inningState.trim().toLowerCase(java.util.Locale.ROOT);
        Matcher english = Pattern.compile("^(top|bottom|bot)\\s*(\\d+)$", Pattern.CASE_INSENSITIVE).matcher(normalized);
        if (english.matches()) {
            return Integer.parseInt(english.group(2)) >= 1;
        }
        Matcher korean = Pattern.compile("^(\\d+)회\\s*[초말]$").matcher(inningState.trim());
        return korean.matches() && Integer.parseInt(korean.group(1)) >= 1;
    }

    private boolean isBasicGameStartTransition(GameState before, GameState after) {
        return before.status() != GameStatus.FINAL
                && !isLiveLike(before.status())
                && after.status() == GameStatus.LIVE;
    }

    private boolean shouldCreateGameStartDraft(GameState before, GameState after, Game game) {
        return isBasicGameStartTransition(before, after)
                && hasReliableLiveStartEvidence(game, after);
    }

    private boolean downgradeUnreliableLiveIfNeeded(Game game, GameState state, String source) {
        if (game.getStatus() != GameStatus.LIVE || hasReliableLiveStartEvidence(game, state)) {
            return false;
        }
        log.info(
                "[LiveGameSync] unreliable LIVE reverted game={} providerGameId={} scheduledAt={} source={} statusReason={} snapshotObservedAt={} inning={} inningHalf={} awayScore={} homeScore={} balls={} strikes={} outs={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getScheduledAt(),
                source,
                game.getStatusReason(),
                state.snapshotObservedAt(),
                state.inning(),
                state.inningHalf(),
                game.getAwayScore(),
                game.getHomeScore(),
                state.balls(),
                state.strikes(),
                state.outs()
        );
        game.syncDetail(
                GameStatus.SCHEDULED,
                game.getHomeScore(),
                game.getAwayScore(),
                game.getInningState(),
                false,
                false,
                null,
                null,
                game.getHomeStartingPitcherName(),
                game.getAwayStartingPitcherName(),
                game.getLineupData(),
                game.getStatusReason(),
                game.getSourceUpdatedAt()
        );
        gameRepository.save(game);
        return true;
    }

    private boolean hasReliableLiveStartEvidence(Game game, GameState state) {
        if (isBeforeScheduledStart(game)) {
            return false;
        }
        return hasExplicitOfficialLiveSignal(game)
                || hasScoreProgress(game)
                || hasFreshSnapshotProgress(game, state);
    }

    private boolean isBeforeScheduledStart(Game game) {
        return game.getScheduledAt() != null
                && Instant.now(applicationClock).isBefore(game.getScheduledAt().toInstant());
    }

    private boolean hasExplicitOfficialLiveSignal(Game game) {
        String statusReason = game.getStatusReason();
        if (statusReason == null || statusReason.isBlank()) {
            return false;
        }
        String lower = statusReason.toLowerCase(java.util.Locale.ROOT);
        String collapsed = statusReason.replace(" ", "");
        return collapsed.contains("경기중")
                || lower.contains("live")
                || lower.contains("in_progress")
                || lower.contains("in progress")
                || lower.contains("running");
    }

    private boolean hasScoreProgress(Game game) {
        return nullSafe(game.getAwayScore()) > 0 || nullSafe(game.getHomeScore()) > 0;
    }

    private boolean hasFreshSnapshotProgress(Game game, GameState state) {
        if (!snapshotAtOrAfterScheduledStart(game, state.snapshotObservedAt())) {
            return false;
        }
        return hasActualSnapshotProgress(state);
    }

    private boolean snapshotAtOrAfterScheduledStart(Game game, OffsetDateTime snapshotObservedAt) {
        if (snapshotObservedAt == null) {
            return false;
        }
        if (game.getScheduledAt() == null) {
            return true;
        }
        return !snapshotObservedAt.toInstant().isBefore(game.getScheduledAt().toInstant());
    }

    private boolean hasActualSnapshotProgress(GameState state) {
        if (state.inning() != null && state.inning() > 1) {
            return true;
        }
        if ("bottom".equals(normalizeHalf(state.inningHalf()))) {
            return true;
        }
        return nullSafe(state.balls()) > 0
                || nullSafe(state.strikes()) > 0
                || nullSafe(state.outs()) > 0
                || baseCount(state) > 0;
    }

    private void logGameStartTiming(Game game, NotificationEventDraft draft, String source) {
        if (game.getScheduledAt() == null) {
            return;
        }
        Instant detectedAt = Instant.now(applicationClock);
        long delayMinutes = Duration.between(game.getScheduledAt().toInstant(), detectedAt).toMinutes();
        log.info(
                "[LiveGameSync] GAME_START timing game={} providerGameId={} scheduledAt={} detectedLiveStatusAt={} eventKey={} source={} delayMinutes={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                game.getScheduledAt(),
                detectedAt,
                draft.eventKey(),
                source,
                delayMinutes
        );
        if (delayMinutes >= 10) {
            log.warn(
                    "[LiveGameSync] delayed GAME_START detected game={} providerGameId={} scheduledAt={} detectedLiveStatusAt={} eventKey={} source={} delayMinutes={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getScheduledAt(),
                    detectedAt,
                    draft.eventKey(),
                    source,
                    delayMinutes
            );
        }
    }

    private boolean shouldRunDetailImport(Game game) {
        if (isLiveLike(game.getStatus())) {
            return true;
        }
        if (game.getStatus() == GameStatus.DELAYED) {
            return true;
        }
        if (game.getStatus() == GameStatus.FINAL && !shouldSkipFinalConfirmed(game)) {
            return true;
        }
        if ((game.getStatus() == GameStatus.SCHEDULED || game.getStatus() == GameStatus.UNKNOWN)
                && hasScheduledStartReached(game)) {
            log.debug(
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
        if ((game.getStatus() == GameStatus.SCHEDULED || game.getStatus() == GameStatus.UNKNOWN || game.getStatus() == GameStatus.DELAYED)
                && hasScheduledStartReached(game)) {
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
        if (shouldCreateGameStartDraft(before, after, game)) {
            drafts.add(startedDraft(game));
        }
        if (isCancellationTransition(before.status(), after.status())) {
            drafts.add(cancelledDraft(game, after.status()));
        }
        if (isInterruptionTransition(before, after)) {
            drafts.add(interruptionDraft(game, before, after));
        }
        String resumeScheduledTime = resumeScheduledTime(before, after);
        if (resumeScheduledTime != null) {
            drafts.add(resumeScheduledDraft(game, resumeScheduledTime));
        }
        if (isResumeTransition(before.status(), after.status())) {
            drafts.add(resumedDraft(game));
        }
        if (isLiveLike(before.status()) && after.status() == GameStatus.FINAL && isStrongFinal(game)) {
            drafts.add(finalDraft(game));
            return drafts;
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
        if (after.status() == GameStatus.LIVE && onBaseChanged(before, after)) {
            drafts.add(onBaseDraft(game, before, after));
        }
        return drafts;
    }

    public List<NotificationEventDraft> replayNotificationDrafts(
            Game game,
            List<GameSnapshot> snapshots,
            Set<String> eventTypes,
            int maxEvents,
            String installationId
    ) {
        if (game == null || snapshots == null || snapshots.isEmpty() || maxEvents <= 0) {
            return List.of();
        }
        Set<String> selectedEventTypes = eventTypes == null ? Set.of() : eventTypes;
        List<NotificationEventDraft> replayDrafts = new ArrayList<>();

        GameSnapshot firstSnapshot = snapshots.get(0);
        Game firstLiveGame = replayGame(game, firstSnapshot, GameStatus.LIVE);
        addReplayDrafts(
                replayDrafts,
                detectChanges(
                        GameState.fromReplay(game, null, GameStatus.SCHEDULED, null, null),
                        GameState.fromReplay(firstLiveGame, firstSnapshot, GameStatus.LIVE, null, firstLiveGame.getStatusReason()),
                        firstLiveGame
                ),
                selectedEventTypes,
                maxEvents,
                game.getId(),
                installationId
        );

        for (int index = 1; index < snapshots.size() && replayDrafts.size() < maxEvents; index++) {
            GameSnapshot beforeSnapshot = snapshots.get(index - 1);
            GameSnapshot afterSnapshot = snapshots.get(index);
            Game beforeGame = replayGame(game, beforeSnapshot, GameStatus.LIVE);
            Game afterGame = replayGame(game, afterSnapshot, GameStatus.LIVE);
            addReplayDrafts(
                    replayDrafts,
                    detectChanges(
                            GameState.fromReplay(beforeGame, beforeSnapshot, GameStatus.LIVE, null, beforeGame.getStatusReason()),
                            GameState.fromReplay(afterGame, afterSnapshot, GameStatus.LIVE, null, afterGame.getStatusReason()),
                            afterGame
                    ),
                    selectedEventTypes,
                    maxEvents,
                    game.getId(),
                    installationId
            );
        }

        if (replayDrafts.size() < maxEvents && game.getStatus() == GameStatus.FINAL) {
            GameSnapshot lastSnapshot = snapshots.get(snapshots.size() - 1);
            Game beforeGame = replayGame(game, lastSnapshot, GameStatus.LIVE);
            Game finalGame = replayGame(game, lastSnapshot, GameStatus.FINAL);
            addReplayDrafts(
                    replayDrafts,
                    detectChanges(
                            GameState.fromReplay(beforeGame, lastSnapshot, GameStatus.LIVE, null, beforeGame.getStatusReason()),
                            GameState.fromReplay(finalGame, lastSnapshot, GameStatus.FINAL, finalGame.getFinalConfirmedAt(), finalGame.getStatusReason()),
                            finalGame
                    ),
                    selectedEventTypes,
                    maxEvents,
                    game.getId(),
                    installationId
            );
        }

        return replayDrafts;
    }

    private void addReplayDrafts(
            List<NotificationEventDraft> replayDrafts,
            List<NotificationEventDraft> candidates,
            Set<String> eventTypes,
            int maxEvents,
            UUID gameId,
            String installationId
    ) {
        for (NotificationEventDraft candidate : candidates) {
            if (replayDrafts.size() >= maxEvents) {
                return;
            }
            if (!eventTypes.isEmpty() && !eventTypes.contains(candidate.eventType())) {
                continue;
            }
            int replayIndex = replayDrafts.size() + 1;
            Map<String, Object> payload = new HashMap<>(candidate.payload());
            payload.put("testReplay", true);
            payload.put("originalEventKey", candidate.eventKey());
            payload.put("replayIndex", replayIndex);
            payload.put("targetInstallationId", installationId);
            replayDrafts.add(new NotificationEventDraft(
                    candidate.eventType(),
                    "test-replay:%s:%s:%s:%d".formatted(
                            gameId,
                            safeReplayKey(installationId),
                            candidate.eventType(),
                            replayIndex
                    ),
                    testReplayTitle(candidate.title()),
                    candidate.body(),
                    payload
            ));
        }
    }

    private String testReplayTitle(String title) {
        if (title == null || title.isBlank()) {
            return "[테스트]";
        }
        if (title.startsWith("[테스트]")) {
            return title;
        }
        return "[테스트] " + title;
    }

    private String safeReplayKey(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        return value.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private Game replayGame(Game source, GameSnapshot snapshot, GameStatus status) {
        Integer homeScore = snapshot == null || snapshot.getHomeScore() == null ? source.getHomeScore() : snapshot.getHomeScore();
        Integer awayScore = snapshot == null || snapshot.getAwayScore() == null ? source.getAwayScore() : snapshot.getAwayScore();
        String inningState = replayInningState(snapshot);
        OffsetDateTime sourceUpdatedAt = snapshot == null || snapshot.getSourceUpdatedAt() == null
                ? source.getSourceUpdatedAt()
                : snapshot.getSourceUpdatedAt();
        String statusReason = status == GameStatus.FINAL
                ? replayFinalStatusReason(source)
                : source.getStatusReason();
        Game replay = new Game(
                source.getId(),
                source.getPublicGameId(),
                source.getProvider(),
                source.getProviderGameId(),
                source.getGameDate(),
                source.getScheduledAt(),
                source.getStadium(),
                status,
                source.getHomeTeam(),
                source.getAwayTeam(),
                homeScore,
                awayScore,
                inningState,
                false,
                false,
                null,
                null,
                source.getHomeStartingPitcherName(),
                source.getAwayStartingPitcherName(),
                sourceUpdatedAt
        );
        replay.syncDetail(
                status,
                homeScore,
                awayScore,
                inningState,
                false,
                false,
                null,
                null,
                source.getHomeStartingPitcherName(),
                source.getAwayStartingPitcherName(),
                source.getLineupData(),
                statusReason,
                sourceUpdatedAt
        );
        if (status == GameStatus.FINAL) {
            replay.confirmFinal(source.getFinalConfirmedAt() == null ? OffsetDateTime.now(applicationClock) : source.getFinalConfirmedAt());
        }
        return replay;
    }

    private String replayFinalStatusReason(Game source) {
        if (hasReliableFinalStatusReason(source.getStatusReason())) {
            return source.getStatusReason();
        }
        return "GAME_RESULT_CK=1";
    }

    private String replayInningState(GameSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        if (snapshot.getInningLabel() != null && !snapshot.getInningLabel().isBlank()) {
            return snapshot.getInningLabel();
        }
        if (snapshot.getInning() == null || snapshot.getInningHalf() == null || snapshot.getInningHalf().isBlank()) {
            return null;
        }
        return "%d %s".formatted(snapshot.getInning(), snapshot.getInningHalf());
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

    private NotificationEventDraft interruptionDraft(Game game, GameState before, GameState after) {
        if (after.status() == GameStatus.DELAYED) {
            return delayedDraft(game);
        }
        return suspendedDraft(game);
    }

    private NotificationEventDraft delayedDraft(Game game) {
        String reason = normalizedInterruptionReason(game);
        String matchup = "%s vs %s".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        String body = isRainInterruption(reason)
                ? "%s 경기가 우천으로 지연되고 있습니다.%s".formatted(matchup, delayedContext(game))
                : "%s 경기가 지연되고 있습니다.%s".formatted(matchup, delayedContext(game));
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_GAME_DELAYED,
                "game:%s:delayed".formatted(game.getId()),
                "경기 지연",
                body
        );
        draft.payload().put("statusReason", game.getStatusReason());
        return draft;
    }

    private NotificationEventDraft suspendedDraft(Game game) {
        String reason = normalizedInterruptionReason(game);
        String matchup = "%s vs %s".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        String body = isRainInterruption(reason)
                ? "%s 경기가 우천으로 일시 중단되었습니다.".formatted(matchup)
                : "%s 경기가 일시 중단되었습니다.".formatted(matchup);
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_GAME_SUSPENDED,
                "game:%s:suspended".formatted(game.getId()),
                "경기 중단",
                body
        );
        draft.payload().put("statusReason", game.getStatusReason());
        return draft;
    }

    private String delayedContext(Game game) {
        String context = cancellationContext(game);
        return context.isBlank() ? "" : context;
    }

    private NotificationEventDraft resumeScheduledDraft(Game game, String resumeTime) {
        String matchup = "%s vs %s".formatted(teamShortName(game, game.getAwayTeam().getTeamCode()), teamShortName(game, game.getHomeTeam().getTeamCode()));
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_GAME_RESUME_SCHEDULED,
                "game:%s:resume-scheduled:%s".formatted(game.getId(), resumeTime),
                "경기 재개 예정",
                "%s 경기가 %s 재개 예정입니다.".formatted(matchup, resumeTime)
        );
        draft.payload().put("statusReason", game.getStatusReason());
        draft.payload().put("expectedResume", resumeTime);
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
        NotificationText fallbackText = scoringFallbackText(scoringContext, result);
        String formattedMessage = detailedText == null ? fallbackText.body() : detailedText.body();
        boolean fallbackUsed = detailedText == null;

        NotificationEventDraft draft = liveDraft(
                game,
                NotificationEventService.EVENT_SCORE_CHANGED,
                "score:%s:%s:%s:%d:%d".formatted(
                        game.getId(),
                        safeKey(after.inning()),
                        safeKey(normalizeHalf(after.inningHalf())),
                        nullSafe(after.awayScore()),
                        nullSafe(after.homeScore())
                ),
                detailedText == null ? fallbackText.title() : detailedText.title(),
                formattedMessage,
                batterName,
                pitcherName,
                result,
                runCount,
                eventTeamId
        );
        addLegacyEventKey(draft, "game:%s:score:%d-%d".formatted(game.getId(), nullSafe(after.awayScore()), nullSafe(after.homeScore())));
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

        String batterName = before.currentBatterName();
        String pitcherName = before.currentPitcherName();
        String result = liveEventResult(game, "출루");
        String eventTeamId = battingTeamId(game);
        OnBasePlayContext onBaseContext = onBasePlayContext(game, before, after, eventTeamId);
        OnBaseDetailResolution onBaseDetailResolution = onBaseDetail(game, onBaseContext);
        NotificationText onBaseText = OnBaseNotificationFormatter.text(onBaseDetailResolution.detail()).orElse(null);
        NotificationText fallbackText = onBaseText == null ? onBaseFallbackText(game, before, after, eventTeamId, result) : null;
        Instant builtAt = Instant.now(applicationClock);

        NotificationEventDraft draft = liveDraft(
                game,
                NotificationEventService.EVENT_ON_BASE,
                "onbase:%s:%s:%s:batter:%s:outs:%s-%s".formatted(
                        game.getId(),
                        safeKey(after.inning()),
                        safeKey(normalizeHalf(after.inningHalf())),
                        safeKey(batterName),
                        safeKey(before.outs()),
                        safeKey(after.outs())
                ),
                onBaseText == null ? fallbackText.title() : onBaseText.title(),
                onBaseText == null ? fallbackText.body() : onBaseText.body(),
                batterName,
                pitcherName,
                result,
                null,
                eventTeamId
        );
        addLegacyEventKey(draft, "game:%s:on-base:inning:%s:bases:%s:batter:%s:pitcher:%s:result:%s".formatted(
                game.getId(),
                game.getInningState() == null ? "경기" : game.getInningState(),
                baseKey(after),
                safeKey(batterName),
                safeKey(pitcherName),
                safeKey(result)
        ));
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
                "end:%s:%d:%d:%s".formatted(game.getId(), nullSafe(game.getAwayScore()), nullSafe(game.getHomeScore()), game.getStatus()),
                title,
                "최종 스코어가 확정되었습니다."
        );
        addLegacyEventKey(draft, "game:%s:game-end".formatted(game.getId()));
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
                "inning:%s:%s:%s".formatted(game.getId(), safeKey(after.inning()), safeKey(normalizeHalf(after.inningHalf()))),
                label,
                "%s로 전환되었습니다.".formatted(label)
        );
        addLegacyEventKey(draft, "game:%s:inning-change:%s:%s".formatted(game.getId(), after.inning(), safeKey(after.inningHalf())));
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
        NotificationText fallbackText = leadChangeFallbackText(scoringContext, before.awayScore(), before.homeScore(), liveEventResult(game, "득점"));
        NotificationEventDraft draft = draft(
                game,
                NotificationEventService.EVENT_LEAD_CHANGED,
                "lead:%s:%s:%s:%d:%d:%s".formatted(
                        game.getId(),
                        safeKey(after.inning()),
                        safeKey(normalizeHalf(after.inningHalf())),
                        nullSafe(after.awayScore()),
                        nullSafe(after.homeScore()),
                        safeKey(eventTeamId)
                ),
                detailedText == null ? fallbackText.title() : detailedText.title(),
                detailedText == null ? fallbackText.body() : detailedText.body()
        );
        addLegacyEventKey(draft, "game:%s:lead-change:%s:%d-%d".formatted(game.getId(), eventTeamId, nullSafe(after.awayScore()), nullSafe(after.homeScore())));
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

    private boolean shouldSkipFinalMutableNotification(Game game, NotificationEventDraft draft) {
        if (game == null || draft == null || !isMutableLiveNotification(draft.eventType())) {
            return false;
        }
        return game.getFinalConfirmedAt() != null || game.getStatus() == GameStatus.FINAL;
    }

    private boolean isMutableLiveNotification(String eventType) {
        return NotificationEventService.EVENT_ON_BASE.equals(eventType)
                || NotificationEventService.EVENT_SCORE_CHANGED.equals(eventType)
                || NotificationEventService.EVENT_INNING_CHANGED.equals(eventType);
    }

    private void logFinalMutableNotificationSkipped(Game game, NotificationEventDraft draft) {
        log.info(
                "[Notifications] skipped reason=game_already_final eventType={} publicGameId={}",
                draft == null ? null : draft.eventType(),
                game == null ? null : game.getPublicGameId()
        );
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

    private void addLegacyEventKey(NotificationEventDraft draft, String legacyEventKey) {
        if (draft == null || legacyEventKey == null || legacyEventKey.isBlank()) {
            return;
        }
        draft.payload().put("legacyEventKeys", List.of(legacyEventKey));
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

    private boolean isInterruptionTransition(GameState before, GameState after) {
        return before.status() != after.status()
                && (before.status() == GameStatus.SCHEDULED || before.status() == GameStatus.UNKNOWN || before.status() == GameStatus.DELAYED || isLiveLike(before.status()) || isWeakFinal(before))
                && isInterrupted(after.status())
                && !isInterrupted(before.status());
    }

    private String resumeScheduledTime(GameState before, GameState after) {
        if (!isInterrupted(after.status())) {
            return null;
        }
        if (!changedText(before.statusReason(), after.statusReason())) {
            return null;
        }
        return extractOfficialResumeTime(after.statusReason());
    }

    private String extractOfficialResumeTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        boolean resumeContext = value.contains("재개") || lower.contains("resume");
        boolean scheduledContext = value.contains("예정") || lower.contains("scheduled") || lower.contains("expected");
        if (!resumeContext || !scheduledContext) {
            return null;
        }
        Matcher matcher = RESUME_TIME_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        return "%02d:%s".formatted(hour, matcher.group(2));
    }

    private boolean isWeakFinal(GameState state) {
        return state.status() == GameStatus.FINAL
                && (state.finalConfirmedAt() == null || !hasReliableFinalStatusReason(state.statusReason()));
    }

    private boolean isResumeTransition(GameStatus before, GameStatus after) {
        return before == GameStatus.SUSPENDED && after == GameStatus.LIVE;
    }

    private boolean isInterrupted(GameStatus status) {
        return status == GameStatus.DELAYED || status == GameStatus.SUSPENDED;
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

    private NotificationText onBaseFallbackText(Game game, GameState before, GameState after, String eventTeamId, String result) {
        String teamName = teamShortName(game, eventTeamId);
        String batterName = before == null ? null : before.currentBatterName();
        OnBasePlayDetail detail = new OnBasePlayDetail(
                hasText(batterName) ? batterName : teamName,
                onBaseFallbackResult(result),
                1,
                before == null || before.inning() == null ? after.inning() : before.inning(),
                before == null || before.inningHalf() == null ? after.inningHalf() : before.inningHalf(),
                eventTeamId,
                teamName,
                after.awayScore(),
                after.homeScore(),
                teamShortName(game, game.getAwayTeam().getTeamCode()),
                teamShortName(game, game.getHomeTeam().getTeamCode()),
                "fallback"
        );
        return OnBaseNotificationFormatter.text(detail)
                .orElseGet(() -> new NotificationText("%s 출루".formatted(teamName), "%s 출루".formatted(teamName)));
    }

    private String onBaseFallbackResult(String result) {
        if (!hasText(result) || "출루".equals(result)) {
            return "출루";
        }
        if ("실책".equals(result)) {
            return "실책으로 출루";
        }
        if ("야수선택".equals(result)) {
            return "야수선택 출루";
        }
        return result;
    }

    private NotificationText scoringFallbackText(ScoringPlayContext context, String result) {
        if (context == null) {
            return new NotificationText("KBO 득점", "득점 상황 발생");
        }
        if (hasText(context.previousBatterName())) {
            ScoringPlayDetail detail = scoringFallbackDetail(context, result);
            return ScoringPlayNotificationFormatter.scoreChangeText(detail)
                    .orElseGet(() -> new NotificationText("%s 득점".formatted(context.battingTeamName()), scoringFallbackBody(context)));
        }
        return new NotificationText(
                "%s 득점".formatted(context.battingTeamName()),
                scoringFallbackBody(context)
        );
    }

    private NotificationText leadChangeFallbackText(
            ScoringPlayContext context,
            Integer previousAwayScore,
            Integer previousHomeScore,
            String result
    ) {
        if (context == null) {
            return new NotificationText("KBO 리드 변경", "득점 상황 발생");
        }
        NotificationText scoringText = scoringFallbackText(context, result);
        return new NotificationText(
                leadChangeFallbackTitle(context, previousAwayScore, previousHomeScore),
                scoringText.body()
        );
    }

    private ScoringPlayDetail scoringFallbackDetail(ScoringPlayContext context, String result) {
        return new ScoringPlayDetail(
                context.previousBatterName(),
                scoringFallbackResult(result),
                null,
                context.scoreDelta(),
                context.scoreDelta(),
                context.inning(),
                context.inningHalf(),
                context.battingTeamId(),
                context.battingTeamName(),
                context.awayScoreAfter(),
                context.homeScoreAfter(),
                context.awayTeamName(),
                context.homeTeamName()
        );
    }

    private String scoringFallbackResult(String result) {
        return hasText(result) && !"득점".equals(result) ? result : "득점 상황";
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

    private boolean onBaseChanged(GameState before, GameState after) {
        if (before == null || after == null) {
            return false;
        }
        boolean baseChanged = !java.util.Objects.equals(baseKey(before), baseKey(after));
        boolean runnerChanged = !java.util.Objects.equals(runnerNamesKey(before), runnerNamesKey(after));
        return (baseChanged || runnerChanged) && baseCount(after) > 0 && nullSafe(after.outs()) <= nullSafe(before.outs());
    }

    private String runnerNamesKey(GameState state) {
        if (state == null) {
            return "-|-|-";
        }
        return "%s|%s|%s".formatted(
                safeKey(state.firstBaseRunnerName()),
                safeKey(state.secondBaseRunnerName()),
                safeKey(state.thirdBaseRunnerName())
        );
    }

    private int nullSafe(Integer value) {
        return value == null ? 0 : value;
    }

    private String safeKey(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
    }

    private String safeKey(Object value) {
        return value == null ? "-" : safeKey(String.valueOf(value));
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

    private void logSnapshotRecoveryDecision(
            Game game,
            GameSnapshot previous,
            GameSnapshot current,
            NotificationEventDraft draft,
            String decision,
            String reason
    ) {
        log.info(
                "[SnapshotNotificationRecovery] gameId={} publicGameId={} prevSnapshotId={} prevCreatedAt={} currentSnapshotId={} currentCreatedAt={} eventType={} eventKey={} decision={} reason={}",
                game == null ? null : game.getId(),
                game == null ? null : game.getPublicGameId(),
                previous == null ? null : previous.getId(),
                previous == null ? null : snapshotObservedAt(previous),
                current == null ? null : current.getId(),
                current == null ? null : snapshotObservedAt(current),
                draft == null ? null : draft.eventType(),
                draft == null ? null : draft.eventKey(),
                decision,
                reason
        );
    }

    private OffsetDateTime snapshotObservedAt(GameSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        return snapshot.getFetchedAt() != null ? snapshot.getFetchedAt() : snapshot.getCreatedAt();
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
            Integer balls,
            Integer strikes,
            Integer outs,
            OffsetDateTime snapshotObservedAt,
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
                    snapshot == null ? null : snapshot.getBalls(),
                    snapshot == null ? null : snapshot.getStrikes(),
                    snapshot == null ? null : snapshot.getOuts(),
                    snapshotObservedAt(snapshot),
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

        static GameState fromReplay(
                Game game,
                GameSnapshot snapshot,
                GameStatus status,
                OffsetDateTime finalConfirmedAt,
                String statusReason
        ) {
            return new GameState(
                    status,
                    false,
                    false,
                    finalConfirmedAt,
                    clean(statusReason),
                    snapshot == null ? null : snapshot.getHomeScore(),
                    snapshot == null ? null : snapshot.getAwayScore(),
                    game.getHomeStartingPitcherName(),
                    game.getAwayStartingPitcherName(),
                    game.getLineupData() == null ? null : String.valueOf(game.getLineupData().hashCode()),
                    snapshot == null ? null : snapshot.getInning(),
                    clean(snapshot == null ? null : snapshot.getInningHalf()),
                    clean(snapshot == null ? null : snapshot.getInningLabel()),
                    snapshot == null ? null : snapshot.getBalls(),
                    snapshot == null ? null : snapshot.getStrikes(),
                    snapshot == null ? null : snapshot.getOuts(),
                    snapshotObservedAt(snapshot),
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

        private static OffsetDateTime snapshotObservedAt(GameSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            return snapshot.getFetchedAt() != null ? snapshot.getFetchedAt() : snapshot.getCreatedAt();
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

    private record SnapshotRecoveryResult(
            int eventCreatedCount,
            int sentCount,
            int skippedCount,
            int failedCount,
            List<String> eventKeys
    ) {
        private static SnapshotRecoveryResult empty() {
            return new SnapshotRecoveryResult(0, 0, 0, 0, List.of());
        }
    }

    public record NotificationRecoveryDiagnosis(
            String gameId,
            String publicGameId,
            int snapshotCount,
            int candidateEventCount,
            long storedEventCount,
            long suspectedMissingEventCount
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
