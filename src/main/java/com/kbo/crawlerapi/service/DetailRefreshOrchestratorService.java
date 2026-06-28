package com.kbo.crawlerapi.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@Service
public class DetailRefreshOrchestratorService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final LineScoreRepository lineScoreRepository;
    private final GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository;
    private final GameDetailImportService gameDetailImportService;
    private final Clock applicationClock;
    private final com.kbo.crawlerapi.config.SchedulerShellProperties schedulerShellProperties;
    private final DateSyncLockService dateSyncLockService;

    public DetailRefreshOrchestratorService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository,
            GameDetailImportService gameDetailImportService,
            Clock applicationClock,
            com.kbo.crawlerapi.config.SchedulerShellProperties schedulerShellProperties
    ) {
        this(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                gameBoxscoreRecordReadRepository,
                gameDetailImportService,
                applicationClock,
                schedulerShellProperties,
                null
        );
    }

    @Autowired
    public DetailRefreshOrchestratorService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository,
            GameDetailImportService gameDetailImportService,
            Clock applicationClock,
            com.kbo.crawlerapi.config.SchedulerShellProperties schedulerShellProperties,
            DateSyncLockService dateSyncLockService
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.gameBoxscoreRecordReadRepository = gameBoxscoreRecordReadRepository;
        this.gameDetailImportService = gameDetailImportService;
        this.applicationClock = applicationClock;
        this.schedulerShellProperties = schedulerShellProperties;
        this.dateSyncLockService = dateSyncLockService == null ? new DateSyncLockService() : dateSyncLockService;
    }

    public DetailRefreshPassResult runPass(LocalDate date, boolean execute) {
        return runPass(date, execute, Set.of(RefreshPhase.PREGAME, RefreshPhase.LIVE, RefreshPhase.POST_FINAL));
    }

    public DetailRefreshPassResult runPass(LocalDate date, boolean execute, Set<RefreshPhase> allowedPhases) {
        LocalDate targetDate = date != null ? date : LocalDate.now(applicationClock);
        Set<RefreshPhase> effectiveAllowedPhases = allowedPhases == null || allowedPhases.isEmpty()
                ? Set.of(RefreshPhase.PREGAME, RefreshPhase.LIVE, RefreshPhase.POST_FINAL)
                : allowedPhases;
        if (!execute) {
            return runPassWithoutLock(targetDate, false, effectiveAllowedPhases);
        }

        LockedPhases lockedPhases = acquireLocks(targetDate, effectiveAllowedPhases);
        try {
            return runPassWithoutLock(targetDate, true, lockedPhases.phases());
        } finally {
            lockedPhases.close();
        }
    }

    private DetailRefreshPassResult runPassWithoutLock(LocalDate targetDate, boolean execute, Set<RefreshPhase> allowedPhases) {
        List<Game> games = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(targetDate);
        List<GameRefreshDecision> decisions = new ArrayList<>(games.size());

        for (Game game : games) {
            decisions.add(evaluate(game, allowedPhases));
        }

        List<GameRefreshExecutionResult> executionResults = execute
                ? executeEligibleGames(decisions)
                : List.of();

        return new DetailRefreshPassResult(
                targetDate,
                execute,
                decisions.size(),
                (int) decisions.stream().filter(GameRefreshDecision::selected).count(),
                decisions,
                executionResults
        );
    }

    private LockedPhases acquireLocks(LocalDate targetDate, Set<RefreshPhase> allowedPhases) {
        EnumSet<RefreshPhase> lockedPhases = EnumSet.noneOf(RefreshPhase.class);
        List<DateSyncLockService.SyncLock> locks = new ArrayList<>();
        for (RefreshPhase phase : List.of(RefreshPhase.PREGAME, RefreshPhase.LIVE, RefreshPhase.POST_FINAL)) {
            if (!allowedPhases.contains(phase)) {
                continue;
            }
            DateSyncLockService.SyncLock lock = dateSyncLockService.tryDetailRefreshLock(targetDate, phase.phaseName());
            if (lock.acquired()) {
                locks.add(lock);
                lockedPhases.add(phase);
            }
        }
        return new LockedPhases(lockedPhases, locks);
    }

    private GameRefreshDecision evaluate(Game game, Set<RefreshPhase> allowedPhases) {
        GameSnapshot latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);
        long lineScoreCount = lineScoreRepository.countByGame_Id(game.getId());
        boolean finalDataComplete = isFinalDataComplete(game, lineScoreCount);

        if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
            return new GameRefreshDecision(
                    game.getPublicGameId(),
                    game.getStatus().getApiValue(),
                    toKst(game.getScheduledAt()),
                    false,
                    RefreshPhase.IDLE.phaseName(),
                    null,
                    "Official provider game ID is not available yet, so detail refresh is deferred.",
                    finalDataComplete,
                    lineScoreCount,
                    latestSnapshot != null,
                    toKst(latestSnapshot == null ? null : latestSnapshot.getFetchedAt())
            );
        }

        if (game.getStatus() == GameStatus.LIVE || game.getStatus() == GameStatus.SUSPENDED) {
            return new GameRefreshDecision(
                    game.getPublicGameId(),
                    game.getStatus().getApiValue(),
                    toKst(game.getScheduledAt()),
                    allowedPhases.contains(RefreshPhase.LIVE),
                    RefreshPhase.LIVE.phaseName(),
                    (int) schedulerShellProperties.getLiveInterval().toSeconds(),
                    "Game is live or suspended and should stay on the fastest refresh cadence.",
                    false,
                    lineScoreCount,
                    latestSnapshot != null,
                    toKst(latestSnapshot == null ? null : latestSnapshot.getFetchedAt())
            );
        }

        if (game.getStatus() == GameStatus.FINAL) {
            if (finalDataComplete) {
                return new GameRefreshDecision(
                        game.getPublicGameId(),
                        game.getStatus().getApiValue(),
                        toKst(game.getScheduledAt()),
                        false,
                        RefreshPhase.STOPPED.phaseName(),
                        null,
                        "Final score and numbered line scores are already present, so post-final refresh can stop.",
                        true,
                        lineScoreCount,
                        latestSnapshot != null,
                        toKst(latestSnapshot == null ? null : latestSnapshot.getFetchedAt())
                );
            }
            return new GameRefreshDecision(
                    game.getPublicGameId(),
                    game.getStatus().getApiValue(),
                    toKst(game.getScheduledAt()),
                    allowedPhases.contains(RefreshPhase.POST_FINAL),
                    RefreshPhase.POST_FINAL.phaseName(),
                    (int) schedulerShellProperties.getPostFinalInterval().toSeconds(),
                    "Game is final but final data is incomplete, so it remains eligible for 1-minute refresh.",
                    false,
                    lineScoreCount,
                    latestSnapshot != null,
                    toKst(latestSnapshot == null ? null : latestSnapshot.getFetchedAt())
            );
        }

        if (game.getStatus() == GameStatus.SCHEDULED || game.getStatus() == GameStatus.UNKNOWN) {
            return new GameRefreshDecision(
                    game.getPublicGameId(),
                    game.getStatus().getApiValue(),
                    toKst(game.getScheduledAt()),
                    allowedPhases.contains(RefreshPhase.PREGAME),
                    RefreshPhase.PREGAME.phaseName(),
                    (int) schedulerShellProperties.getPregameInterval().toSeconds(),
                    "Game is not live yet and remains eligible for 30-minute pregame refresh.",
                    false,
                    lineScoreCount,
                    latestSnapshot != null,
                    toKst(latestSnapshot == null ? null : latestSnapshot.getFetchedAt())
            );
        }

        return new GameRefreshDecision(
                game.getPublicGameId(),
                game.getStatus().getApiValue(),
                toKst(game.getScheduledAt()),
                false,
                RefreshPhase.IDLE.phaseName(),
                null,
                "Game status does not require detail refresh.",
                false,
                lineScoreCount,
                latestSnapshot != null,
                toKst(latestSnapshot == null ? null : latestSnapshot.getFetchedAt())
        );
    }

    private List<GameRefreshExecutionResult> executeEligibleGames(List<GameRefreshDecision> decisions) {
        List<GameRefreshExecutionResult> results = new ArrayList<>();
        for (GameRefreshDecision decision : decisions) {
            if (!decision.selected()) {
                continue;
            }
            try {
                GameDetailImportResult importResult = gameDetailImportService.importGameDetail(decision.gameId());
                results.add(new GameRefreshExecutionResult(
                        decision.gameId(),
                        true,
                        null,
                        importResult.snapshotCreated(),
                        importResult.lineScoresUpdated(),
                        importResult.lineScoreCount()
                ));
            } catch (RuntimeException exception) {
                results.add(new GameRefreshExecutionResult(
                        decision.gameId(),
                        false,
                        exception.getMessage(),
                        null,
                        null,
                        null
                ));
            }
        }
        return results;
    }

    private boolean isFinalDataComplete(Game game, long lineScoreCount) {
        if (game.getStatus() != GameStatus.FINAL
                || game.getHomeScore() == null
                || game.getAwayScore() == null
                || lineScoreCount <= 0) {
            return false;
        }
        long batterRecordCount = gameBoxscoreRecordReadRepository.countBatterRecords(game.getId());
        long pitcherRecordCount = gameBoxscoreRecordReadRepository.countPitcherRecords(game.getId());
        return batterRecordCount > 0 && pitcherRecordCount > 0;
    }

    private OffsetDateTime toKst(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toOffsetDateTime();
    }

    private record LockedPhases(
            Set<RefreshPhase> phases,
            List<DateSyncLockService.SyncLock> locks
    ) implements AutoCloseable {

        @Override
        public void close() {
            for (DateSyncLockService.SyncLock lock : locks) {
                lock.close();
            }
        }
    }

    public record DetailRefreshPassResult(
            LocalDate date,
            boolean executed,
            int totalGames,
            int selectedGameCount,
            List<GameRefreshDecision> decisions,
            List<GameRefreshExecutionResult> executionResults
    ) {
    }

    public record GameRefreshDecision(
            String gameId,
            String status,
            OffsetDateTime scheduledAt,
            boolean selected,
            String phase,
            Integer refreshIntervalSeconds,
            String reason,
            boolean finalDataComplete,
            long lineScoreCount,
            boolean hasSnapshot,
            OffsetDateTime latestSnapshotFetchedAt
    ) {
    }

    public record GameRefreshExecutionResult(
            String gameId,
            boolean succeeded,
            String errorMessage,
            Boolean snapshotCreated,
            Boolean lineScoresUpdated,
            Integer lineScoreCount
    ) {
    }

    public enum RefreshPhase {
        PREGAME("pregame"),
        LIVE("live"),
        POST_FINAL("post-final"),
        STOPPED("stopped"),
        IDLE("idle");

        private final String phaseName;

        RefreshPhase(String phaseName) {
            this.phaseName = phaseName;
        }

        public String phaseName() {
            return phaseName;
        }
    }
}
