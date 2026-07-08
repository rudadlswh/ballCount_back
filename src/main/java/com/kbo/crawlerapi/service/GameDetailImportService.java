package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.crawler.KboGameDetailClient.DetailResponse;
import com.kbo.crawlerapi.crawler.KboLiveTextClient;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.parser.KboBoxscoreParser;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBoxscore;
import com.kbo.crawlerapi.parser.KboGameDetailParser;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedGameDetail;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupData;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupPlayer;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedScoreBoardStatus;
import com.kbo.crawlerapi.parser.KboLineScoreParser;
import com.kbo.crawlerapi.parser.KboLineScoreParser.ParsedLineScoreResult;
import com.kbo.crawlerapi.parser.KboLiveTextParser;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;
import com.kbo.crawlerapi.support.HashSupport;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class GameDetailImportService {

    private static final Logger log = LoggerFactory.getLogger(GameDetailImportService.class);
    private static final ObjectMapper DEFAULT_OBJECT_MAPPER = new ObjectMapper();
    private static final DateTimeFormatter OFFICIAL_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final Map<String, String> OFFICIAL_TEAM_CODES_BY_TEAM_CODE = Map.ofEntries(
            Map.entry("doosan", "OB"),
            Map.entry("hanwha", "HH"),
            Map.entry("kia", "HT"),
            Map.entry("kiwoom", "WO"),
            Map.entry("kt", "KT"),
            Map.entry("lg", "LG"),
            Map.entry("lotte", "LT"),
            Map.entry("nc", "NC"),
            Map.entry("samsung", "SS"),
            Map.entry("ssg", "SK")
    );

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final LineScoreRepository lineScoreRepository;
    private final KboGameDetailClient kboGameDetailClient;
    private final KboGameDetailParser kboGameDetailParser;
    private final KboLineScoreParser kboLineScoreParser;
    private final KboBoxscoreParser kboBoxscoreParser;
    private final KboLiveTextClient kboLiveTextClient;
    private final KboLiveTextParser kboLiveTextParser;
    private final GameBoxscoreRecordService gameBoxscoreRecordService;
    private final GameLiveTextRecordService gameLiveTextRecordService;
    private final LiveActivityUpdateService liveActivityUpdateService;
    private final LiveGameStreamPublisher liveGameStreamPublisher;
    private final CrawlJobTrackingService crawlJobTrackingService;
    private final BaseRunnerNameResolver baseRunnerNameResolver;
    private final ObjectMapper objectMapper;

    public GameDetailImportService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            KboGameDetailClient kboGameDetailClient,
            KboGameDetailParser kboGameDetailParser,
            KboLineScoreParser kboLineScoreParser,
            KboBoxscoreParser kboBoxscoreParser,
            GameBoxscoreRecordService gameBoxscoreRecordService,
            CrawlJobTrackingService crawlJobTrackingService,
            BaseRunnerNameResolver baseRunnerNameResolver
    ) {
        this(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                kboGameDetailClient,
                kboGameDetailParser,
                kboLineScoreParser,
                kboBoxscoreParser,
                null,
                null,
                gameBoxscoreRecordService,
                null,
                null,
                null,
                crawlJobTrackingService,
                baseRunnerNameResolver,
                DEFAULT_OBJECT_MAPPER
        );
    }

    public GameDetailImportService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            KboGameDetailClient kboGameDetailClient,
            KboGameDetailParser kboGameDetailParser,
            KboLineScoreParser kboLineScoreParser,
            KboBoxscoreParser kboBoxscoreParser,
            KboLiveTextClient kboLiveTextClient,
            KboLiveTextParser kboLiveTextParser,
            GameBoxscoreRecordService gameBoxscoreRecordService,
            GameLiveTextRecordService gameLiveTextRecordService,
            CrawlJobTrackingService crawlJobTrackingService,
            BaseRunnerNameResolver baseRunnerNameResolver
    ) {
        this(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                kboGameDetailClient,
                kboGameDetailParser,
                kboLineScoreParser,
                kboBoxscoreParser,
                kboLiveTextClient,
                kboLiveTextParser,
                gameBoxscoreRecordService,
                gameLiveTextRecordService,
                null,
                null,
                crawlJobTrackingService,
                baseRunnerNameResolver,
                DEFAULT_OBJECT_MAPPER
        );
    }

    @Autowired
    public GameDetailImportService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            KboGameDetailClient kboGameDetailClient,
            KboGameDetailParser kboGameDetailParser,
            KboLineScoreParser kboLineScoreParser,
            KboBoxscoreParser kboBoxscoreParser,
            KboLiveTextClient kboLiveTextClient,
            KboLiveTextParser kboLiveTextParser,
            GameBoxscoreRecordService gameBoxscoreRecordService,
            GameLiveTextRecordService gameLiveTextRecordService,
            LiveActivityUpdateService liveActivityUpdateService,
            LiveGameStreamPublisher liveGameStreamPublisher,
            CrawlJobTrackingService crawlJobTrackingService,
            BaseRunnerNameResolver baseRunnerNameResolver,
            ObjectMapper objectMapper
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.kboGameDetailClient = kboGameDetailClient;
        this.kboGameDetailParser = kboGameDetailParser;
        this.kboLineScoreParser = kboLineScoreParser;
        this.kboBoxscoreParser = kboBoxscoreParser;
        this.kboLiveTextClient = kboLiveTextClient;
        this.kboLiveTextParser = kboLiveTextParser;
        this.gameBoxscoreRecordService = gameBoxscoreRecordService;
        this.gameLiveTextRecordService = gameLiveTextRecordService;
        this.liveActivityUpdateService = liveActivityUpdateService;
        this.liveGameStreamPublisher = liveGameStreamPublisher;
        this.crawlJobTrackingService = crawlJobTrackingService;
        this.baseRunnerNameResolver = baseRunnerNameResolver;
        this.objectMapper = objectMapper;
    }

    public GameDetailImportService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            KboGameDetailClient kboGameDetailClient,
            KboGameDetailParser kboGameDetailParser,
            KboLineScoreParser kboLineScoreParser,
            KboBoxscoreParser kboBoxscoreParser,
            KboLiveTextClient kboLiveTextClient,
            KboLiveTextParser kboLiveTextParser,
            GameBoxscoreRecordService gameBoxscoreRecordService,
            GameLiveTextRecordService gameLiveTextRecordService,
            LiveActivityUpdateService liveActivityUpdateService,
            CrawlJobTrackingService crawlJobTrackingService,
            BaseRunnerNameResolver baseRunnerNameResolver
    ) {
        this(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                kboGameDetailClient,
                kboGameDetailParser,
                kboLineScoreParser,
                kboBoxscoreParser,
                kboLiveTextClient,
                kboLiveTextParser,
                gameBoxscoreRecordService,
                gameLiveTextRecordService,
                liveActivityUpdateService,
                null,
                crawlJobTrackingService,
                baseRunnerNameResolver,
                DEFAULT_OBJECT_MAPPER
        );
    }

    @Transactional
    public GameDetailImportResult importGameDetail(String publicGameId) {
        var crawlJob = crawlJobTrackingService.createRunningGameDetailImportJob(publicGameId);
        Game game;
        DetailResponse detailResponse;
        String lineScoreResponseBody;
        ResolvedOfficialDetail resolvedOfficialDetail;
        String officialProviderGameId = null;
        ParsedGameDetail parsedDetail;
        ParsedLineScoreResult lineScoreResult;
        ParsedLineupData lineupData;
        BoxscoreFetchResult boxscoreFetchResult;

        try {
            game = gameRepository.findByPublicGameId(publicGameId)
                    .or(() -> gameRepository.findByProviderAndProviderGameId("kbo", publicGameId))
                    .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + publicGameId));
            if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
                throw new IllegalStateException("Provider game ID is not available yet for game " + publicGameId);
            }
            log.info(
                    "[GameDetailImport] start publicGameId={} providerGameId={} status={} finalConfirmedAt={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getStatus(),
                    game.getFinalConfirmedAt()
            );
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "lookup", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            detailResponse = kboGameDetailClient.fetchGameListResponse(game.getGameDate());
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "request", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            resolvedOfficialDetail = resolveOfficialDetail(game, detailResponse.body());
            officialProviderGameId = resolvedOfficialDetail.providerGameId();
            parsedDetail = resolvedOfficialDetail.parsedDetail();
            logCurrentPlayerDtoState(game, parsedDetail);
            LineScoreFetchResult lineScoreFetchResult = fetchLineScoreSafely(
                    game,
                    resolvedOfficialDetail.providerGameId()
            );
            lineScoreResponseBody = lineScoreFetchResult.responseBody();
            lineScoreResult = lineScoreFetchResult.lineScoreResult();
            parsedDetail = applyScoreBoardStatusIfNeeded(game, resolvedOfficialDetail.providerGameId(), parsedDetail, lineScoreResponseBody);
        } catch (Exception exception) {
            logDetailParseFailure(game, detailResponse, officialProviderGameId, exception);
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            backfillProviderGameIdIfNeeded(game, resolvedOfficialDetail.providerGameId());
            OffsetDateTime fetchedAt = OffsetDateTime.now();
            SelectedScore selectedScore = selectScore(game, parsedDetail, lineScoreResult);
            LiveTextFetchResult liveTextFetchResult = fetchLiveTextIfAvailable(
                    game,
                    resolvedOfficialDetail.providerGameId(),
                    parsedDetail
            );
            ParsedLineupData liveTextLineupData = liveTextFetchResult.parsedLiveText() == null
                    ? null
                    : lineupDataForLiveText(game, null, liveTextFetchResult.parsedLiveText());
            parsedDetail = applyRunnerNamesFromLineup(game, parsedDetail, liveTextLineupData);
            String combinedRawHash = HashSupport.sha256Hex(parsedDetail.rawHash() + ":" + lineScoreResult.rawHash() + ":score:" + selectedScore.awayScore() + ":" + selectedScore.homeScore());
            if (rawHashUnchanged(game, parsedDetail, combinedRawHash)) {
                int existingLineScoreCount = existingLineScoreCount(game);
                log.info(
                        "[RealtimeFlow] source unchanged publicGameId={} providerGameId={} rawHash={} action=skip_snapshot_line_score_notification_live_activity",
                        game.getPublicGameId(),
                        resolvedOfficialDetail.providerGameId(),
                        combinedRawHash
                );
                markDetailImportJob(crawlJob.getId(), false, existingLineScoreCount, parsedDetail, BoxscoreImportResult.skipped("unchangedRawHash"));
                return new GameDetailImportResult(
                        game.getPublicGameId(),
                        game.getProviderGameId(),
                        game.getGameDate(),
                        parsedDetail.status().getApiValue(),
                        false,
                        false,
                        existingLineScoreCount,
                        selectedScore.awayScore(),
                        selectedScore.homeScore(),
                        parsedDetail.inning(),
                        parsedDetail.inningHalf(),
                        parsedDetail.inningLabel(),
                        parsedDetail.sourceUpdatedAt(),
                        fetchedAt
                );
            }
            boxscoreFetchResult = fetchBoxscoreIfLineupAvailable(resolvedOfficialDetail.providerGameId(), game.getGameDate().getYear(), parsedDetail);
            lineupData = boxscoreFetchResult.lineupData();
            ParsedLineupData effectiveLineupData = liveTextFetchResult.parsedLiveText() == null
                    ? lineupData
                    : lineupDataForLiveText(game, lineupData, liveTextFetchResult.parsedLiveText());
            parsedDetail = applyRunnerNamesFromLineup(game, parsedDetail, effectiveLineupData);
            GameStatus previousStatus = game.getStatus();
            boolean snapshotCreated = persistSnapshotIfChanged(game, parsedDetail, lineScoreResult, selectedScore, combinedRawHash, fetchedAt);
            boolean lineScoresUpdated = syncLineScoresIfChanged(game, lineScoreResult.innings());
            LiveActivityContentAffectingState beforeLiveActivityState = LiveActivityContentAffectingState.from(game);
            game.syncDetail(
                    parsedDetail.status(),
                    selectedScore.homeScore(),
                    selectedScore.awayScore(),
                    parsedDetail.inningLabel(),
                    parsedDetail.isCancelled(),
                    parsedDetail.isPostponed(),
                    parsedDetail.cancelReason(),
                    parsedDetail.rawCancelText(),
                    parsedDetail.homeStartingPitcherName(),
                    parsedDetail.awayStartingPitcherName(),
                    toLineupJson(lineupData),
                    parsedDetail.statusReason(),
                    parsedDetail.sourceUpdatedAt()
            );
            boolean gameContentStateChanged = !beforeLiveActivityState.equals(LiveActivityContentAffectingState.from(game));
            boolean streamStateChanged = snapshotCreated || gameContentStateChanged;
            boolean statusChanged = previousStatus != game.getStatus();
            importLiveTextIfAvailable(game, resolvedOfficialDetail.providerGameId(), parsedDetail, lineupData, fetchedAt, liveTextFetchResult);
            BoxscoreImportResult boxscoreImportResult = saveBoxscoreRecordsIfAvailable(
                    game,
                    resolvedOfficialDetail.providerGameId(),
                    parsedDetail,
                    boxscoreFetchResult
            );
            markDetailImportJob(crawlJob.getId(), snapshotCreated, lineScoreResult.innings().size(), parsedDetail, boxscoreImportResult);
            boolean liveActivityContentMayHaveChanged = snapshotCreated || lineScoresUpdated || gameContentStateChanged;
            log.info(
                    "[RealtimeFlow] source changed publicGameId={} providerGameId={} rawHash={} snapshotCreated={} lineScoresUpdated={} gameContentStateChanged={} liveActivityUpdateQueued={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    combinedRawHash,
                    snapshotCreated,
                    lineScoresUpdated,
                    gameContentStateChanged,
                    liveActivityContentMayHaveChanged && liveActivityUpdateService != null
            );
            scheduleLiveActivityUpdateAfterCommit(game, parsedDetail, liveActivityContentMayHaveChanged);
            scheduleLiveGameStreamUpdateAfterCommit(game, streamStateChanged, snapshotCreated, statusChanged);

            return new GameDetailImportResult(
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getGameDate(),
                    parsedDetail.status().getApiValue(),
                    snapshotCreated,
                    lineScoresUpdated,
                    lineScoreResult.innings().size(),
                    selectedScore.awayScore(),
                    selectedScore.homeScore(),
                    parsedDetail.inning(),
                    parsedDetail.inningHalf(),
                    parsedDetail.inningLabel(),
                    parsedDetail.sourceUpdatedAt(),
                    fetchedAt
            );
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "persist", exception.getMessage(), exception, 0);
            throw exception;
        }
    }

    private LiveTextImportResult importLiveTextIfAvailable(
            Game game,
            String providerGameId,
            ParsedGameDetail parsedDetail,
            ParsedLineupData lineupData,
            OffsetDateTime fetchedAt
    ) {
        return importLiveTextIfAvailable(
                game,
                providerGameId,
                parsedDetail,
                lineupData,
                fetchedAt,
                fetchLiveTextIfAvailable(game, providerGameId, parsedDetail)
        );
    }

    private LiveTextImportResult importLiveTextIfAvailable(
            Game game,
            String providerGameId,
            ParsedGameDetail parsedDetail,
            ParsedLineupData lineupData,
            OffsetDateTime fetchedAt,
            LiveTextFetchResult liveTextFetchResult
    ) {
        if (gameLiveTextRecordService == null) {
            return LiveTextImportResult.skipped("notConfigured");
        }
        if (liveTextFetchResult.parsedLiveText() == null) {
            return LiveTextImportResult.skipped(liveTextFetchResult.skippedReason());
        }
        ParsedLineupData effectiveLineupData = lineupDataForLiveText(game, lineupData, liveTextFetchResult.parsedLiveText());
        logLiveTextLineupTransfer(game, providerGameId, lineupData, effectiveLineupData);
        var result = gameLiveTextRecordService.saveLiveText(game, liveTextFetchResult.parsedLiveText(), fetchedAt, effectiveLineupData);
        log.info(
                "[KboLiveText] imported gameId={} providerGameId={} status={} batters={} pitchers={} events={}",
                game.getPublicGameId(),
                providerGameId,
                parsedDetail.status(),
                result.batterRecordCount(),
                result.pitcherRecordCount(),
                result.eventCount()
        );
        return new LiveTextImportResult(result.batterRecordCount(), result.pitcherRecordCount(), result.eventCount(), null);
    }

    private LiveTextFetchResult fetchLiveTextIfAvailable(
            Game game,
            String providerGameId,
            ParsedGameDetail parsedDetail
    ) {
        if (kboLiveTextClient == null || kboLiveTextParser == null) {
            return LiveTextFetchResult.skipped("notConfigured");
        }
        if (parsedDetail.status() == GameStatus.FINAL) {
            return LiveTextFetchResult.skipped("finalBoxscorePriority");
        }
        if (providerGameId == null || providerGameId.isBlank()) {
            return LiveTextFetchResult.skipped("missingProviderGameId");
        }
        try {
            var response = kboLiveTextClient.fetchLiveText(providerGameId, game.getGameDate().getYear());
            var parsedLiveText = kboLiveTextParser.parse(response.body());
            if (!parsedLiveText.hasRecords() && !parsedLiveText.hasEvents()) {
                log.info(
                        "[KboLiveText] skipped reason=empty providerGameId={} status={} responseType={} bodyLength={}",
                        providerGameId,
                        parsedDetail.status(),
                        response.responseType(),
                        response.bodyLength()
                );
                return LiveTextFetchResult.skipped("empty");
            }
            return new LiveTextFetchResult(parsedLiveText, null);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KboLiveText] skipped reason=error gameId={} providerGameId={} status={} error={}",
                    game.getPublicGameId(),
                    providerGameId,
                    parsedDetail.status(),
                    exception.getMessage()
            );
            return LiveTextFetchResult.skipped("error:" + exception.getMessage());
        }
    }

    private ParsedLineupData lineupDataForLiveText(Game game, ParsedLineupData lineupData, KboLiveTextParser.ParsedLiveText parsedLiveText) {
        if (lineupData != null && lineupData.hasLineups()) {
            return lineupData;
        }
        List<ParsedLineupPlayer> away = parsedLiveText.awayBatters().stream()
                .filter(record -> record.playerName() != null && !record.playerName().isBlank())
                .map(record -> new ParsedLineupPlayer(
                        String.valueOf(record.battingOrder() == null ? record.sourceOrder() + 1 : record.battingOrder()),
                        record.position(),
                        record.playerName(),
                        game.getAwayTeam() == null ? null : game.getAwayTeam().getTeamCode()
                ))
                .toList();
        List<ParsedLineupPlayer> home = parsedLiveText.homeBatters().stream()
                .filter(record -> record.playerName() != null && !record.playerName().isBlank())
                .map(record -> new ParsedLineupPlayer(
                        String.valueOf(record.battingOrder() == null ? record.sourceOrder() + 1 : record.battingOrder()),
                        record.position(),
                        record.playerName(),
                        game.getHomeTeam() == null ? null : game.getHomeTeam().getTeamCode()
                ))
                .toList();
        if (away.isEmpty() && home.isEmpty()) {
            return lineupData == null ? ParsedLineupData.empty(null) : lineupData;
        }
        return new ParsedLineupData(
                away,
                home,
                "live-text-lineup-fallback",
                game.getAwayTeam() == null ? null : game.getAwayTeam().getTeamCode(),
                game.getHomeTeam() == null ? null : game.getHomeTeam().getTeamCode()
        );
    }

    private ParsedGameDetail applyRunnerNamesFromLineup(
            Game game,
            ParsedGameDetail parsedDetail,
            ParsedLineupData lineupData
    ) {
        ParsedGameDetail resolved = kboGameDetailParser.applyOfficialRunnerNamesFromLineup(parsedDetail, lineupData);
        log.info(
                "[BaseRunners] resolvedFromLineup publicGameId={} half={} offenseTeamCode={} firstOrder={} firstName={} secondOrder={} secondName={} thirdOrder={} thirdName={}",
                game.getPublicGameId(),
                parsedDetail.inningHalf(),
                offenseTeamCode(game, parsedDetail.inningHalf()),
                parsedDetail.firstBaseBattingOrder(),
                displayName(resolved.firstBaseRunnerName()),
                parsedDetail.secondBaseBattingOrder(),
                displayName(resolved.secondBaseRunnerName()),
                parsedDetail.thirdBaseBattingOrder(),
                displayName(resolved.thirdBaseRunnerName())
        );
        return resolved;
    }

    private String offenseTeamCode(Game game, String inningHalf) {
        String normalizedHalf = clean(inningHalf);
        if (normalizedHalf == null) {
            return null;
        }
        String lower = normalizedHalf.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("top") || "초".equals(normalizedHalf)) {
            return game.getAwayTeam() == null ? null : game.getAwayTeam().getTeamCode();
        }
        if (lower.startsWith("bot") || lower.startsWith("bottom") || "말".equals(normalizedHalf)) {
            return game.getHomeTeam() == null ? null : game.getHomeTeam().getTeamCode();
        }
        return null;
    }

    private void logLiveTextLineupTransfer(
            Game game,
            String providerGameId,
            ParsedLineupData originalLineupData,
            ParsedLineupData effectiveLineupData
    ) {
        log.info(
                "[KboLiveTextLineup] gameId={} publicGameId={} providerGameId={} originalLineupCount={} effectiveLineupCount={} awayTeamId={} homeTeamId={} awayTeamCode={} homeTeamCode={}",
                game.getId(),
                game.getPublicGameId(),
                providerGameId,
                lineupCount(originalLineupData),
                lineupCount(effectiveLineupData),
                game.getAwayTeam() == null ? null : game.getAwayTeam().getId(),
                game.getHomeTeam() == null ? null : game.getHomeTeam().getId(),
                game.getAwayTeam() == null ? null : game.getAwayTeam().getTeamCode(),
                game.getHomeTeam() == null ? null : game.getHomeTeam().getTeamCode()
        );
        logLineupSamples(game.getAwayTeam() == null ? null : game.getAwayTeam().getId(), effectiveLineupData == null ? List.of() : effectiveLineupData.away());
        logLineupSamples(game.getHomeTeam() == null ? null : game.getHomeTeam().getId(), effectiveLineupData == null ? List.of() : effectiveLineupData.home());
    }

    private int lineupCount(ParsedLineupData lineupData) {
        if (lineupData == null) {
            return 0;
        }
        return lineupData.away().size() + lineupData.home().size();
    }

    private void logLineupSamples(UUID teamId, List<ParsedLineupPlayer> lineup) {
        lineup.stream().limit(3).forEach(player -> log.info(
                "[KboLiveTextLineupSample] teamId={} teamCode={} battingOrder={} playerName={} position={}",
                teamId,
                player.teamCode(),
                player.battingOrder(),
                player.name(),
                player.position()
        ));
    }

    private void scheduleLiveActivityUpdateAfterCommit(
            Game game,
            ParsedGameDetail parsedDetail,
            boolean contentStateMayHaveChanged
    ) {
        if (liveActivityUpdateService == null) {
            return;
        }
        if (!contentStateMayHaveChanged) {
            log.debug(
                    "[LiveActivity] update skipped publicGameId={} providerGameId={} databaseId={} reason=content_state_unchanged",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId()
            );
            return;
        }
        if (!isLiveActivityUpdateTarget(parsedDetail.status())) {
            log.debug(
                    "[LiveActivity] update skipped publicGameId={} providerGameId={} databaseId={} reason=non_live_status status={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    parsedDetail.status()
            );
            return;
        }
        Runnable delivery = () -> deliverLiveActivityUpdate(game, parsedDetail);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delivery.run();
                }
            });
            log.debug(
                    "[LiveActivity] update scheduled after commit publicGameId={} providerGameId={} databaseId={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId()
            );
            return;
        }
        delivery.run();
    }

    private void scheduleLiveGameStreamUpdateAfterCommit(Game game, boolean streamStateChanged, boolean snapshotCreated, boolean statusChanged) {
        if (liveGameStreamPublisher == null || !streamStateChanged) {
            return;
        }
        log.info(
                "[SseStream] publish requested: publicGameId={} snapshotCreated={} statusChanged={}",
                game.getPublicGameId(),
                snapshotCreated,
                statusChanged
        );
        liveGameStreamPublisher.publishAfterCommit(game.getPublicGameId(), snapshotCreated, statusChanged, game.getStatus());
    }

    private boolean rawHashUnchanged(Game game, ParsedGameDetail parsedDetail, String combinedRawHash) {
        if (hasStatusReasonStateChange(game, parsedDetail)) {
            return false;
        }
        if (!hasMeaningfulLiveState(parsedDetail)
                && !isLiveLike(parsedDetail.status())
                && !isTerminalOrCancellationStatus(parsedDetail.status())) {
            return false;
        }
        return gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .filter(snapshot -> combinedRawHash.equals(snapshot.getRawHash()))
                .filter(snapshot -> !hasResolvedRunnerNameChange(snapshot, parsedDetail, baseRunnerNameResolver.resolve(snapshot, parsedDetail)))
                .isPresent();
    }

    private boolean hasResolvedRunnerNameChange(
            GameSnapshot snapshot,
            ParsedGameDetail parsedDetail,
            BaseRunnerNameResolver.ResolvedBaseRunners resolved
    ) {
        return parsedDetail.runnerOnFirst()
                && clean(resolved.firstBaseRunnerName()) != null
                && !Objects.equals(clean(snapshot.getFirstBaseRunnerName()), clean(resolved.firstBaseRunnerName()))
                || parsedDetail.runnerOnSecond()
                && clean(resolved.secondBaseRunnerName()) != null
                && !Objects.equals(clean(snapshot.getSecondBaseRunnerName()), clean(resolved.secondBaseRunnerName()))
                || parsedDetail.runnerOnThird()
                && clean(resolved.thirdBaseRunnerName()) != null
                && !Objects.equals(clean(snapshot.getThirdBaseRunnerName()), clean(resolved.thirdBaseRunnerName()));
    }

    private int existingLineScoreCount(Game game) {
        return lineScoreRepository.findByGame_IdOrderByInningNumberAsc(game.getId()).size();
    }

    private boolean hasStatusReasonStateChange(Game game, ParsedGameDetail parsedDetail) {
        boolean trackedStatus = isInterruptedStatus(game.getStatus())
                || isInterruptedStatus(parsedDetail.status())
                || isTerminalOrCancellationStatus(parsedDetail.status());
        if (!trackedStatus) {
            return false;
        }
        return game.getStatus() != parsedDetail.status()
                || !Objects.equals(clean(game.getStatusReason()), clean(parsedDetail.statusReason()))
                || game.isCancelled() != parsedDetail.isCancelled()
                || game.isPostponed() != parsedDetail.isPostponed()
                || !Objects.equals(clean(game.getRawCancelText()), clean(parsedDetail.rawCancelText()));
    }

    private boolean isLiveActivityUpdateTarget(GameStatus status) {
        return isLiveLike(status) || isTerminalOrCancellationStatus(status);
    }

    private boolean isTerminalOrCancellationStatus(GameStatus status) {
        return status == GameStatus.FINAL || status == GameStatus.CANCELLED || status == GameStatus.POSTPONED;
    }

    private void deliverLiveActivityUpdate(Game game, ParsedGameDetail parsedDetail) {
        try {
            LiveActivityUpdateService.LiveActivityDeliveryResult result = liveActivityUpdateService.deliverUpdate(game);
            log.info(
                    "[LiveActivity] state-driven update completed publicGameId={} providerGameId={} databaseId={} sent={} skipped={} failed={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    result.sentCount(),
                    result.skippedCount(),
                    result.failedCount()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[LiveActivity] state-driven update failed publicGameId={} providerGameId={} databaseId={} reason={}",
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getId(),
                    exception.getMessage()
            );
        }
    }

    private record LiveActivityContentAffectingState(
            GameStatus status,
            Integer homeScore,
            Integer awayScore,
            String inningState,
            String homeStartingPitcherName,
            String awayStartingPitcherName,
            boolean cancelled,
            boolean postponed
    ) {

        private static LiveActivityContentAffectingState from(Game game) {
            return new LiveActivityContentAffectingState(
                    game.getStatus(),
                    game.getHomeScore(),
                    game.getAwayScore(),
                    game.getInningState(),
                    game.getHomeStartingPitcherName(),
                    game.getAwayStartingPitcherName(),
                    game.isCancelled(),
                    game.isPostponed()
            );
        }
    }

    private BoxscoreFetchResult fetchBoxscoreIfLineupAvailable(String providerGameId, int seasonId, ParsedGameDetail parsedDetail) {
        if (!parsedDetail.lineupAvailable() && parsedDetail.status() != GameStatus.FINAL) {
            return BoxscoreFetchResult.empty("lineupUnavailableNonFinal");
        }
        try {
            log.info(
                    "[GameBoxscoreImport] source fetch start source=GetBoxScoreScroll providerGameId={} status={} lineupAvailable={}",
                    providerGameId,
                    parsedDetail.status(),
                    parsedDetail.lineupAvailable()
            );
            String responseBody = kboGameDetailClient.fetchBoxScore(providerGameId, seasonId);
            ParsedLineupData lineupData = kboGameDetailParser.parseLineupData(responseBody);
            return new BoxscoreFetchResult(responseBody, lineupData, "GetBoxScoreScroll", null);
        } catch (RuntimeException exception) {
            log.warn(
                    "[GameBoxscoreImport] source fetch failed source=GetBoxScoreScroll providerGameId={} status={} reason={}",
                    providerGameId,
                    parsedDetail.status(),
                    exception.getMessage()
            );
            return BoxscoreFetchResult.empty("fetchFailed:" + exception.getMessage());
        }
    }

    private LineScoreFetchResult fetchLineScoreSafely(Game game, String providerGameId) {
        try {
            String responseBody = kboGameDetailClient.fetchScoreBoard(providerGameId, game.getGameDate().getYear());
            return new LineScoreFetchResult(responseBody, kboLineScoreParser.parse(responseBody));
        } catch (RuntimeException exception) {
            log.info(
                    "[GameDetailImport] scoreboard line score unavailable publicGameId={} providerGameId={} reason={}",
                    game.getPublicGameId(),
                    providerGameId,
                    exception.getMessage()
            );
            return new LineScoreFetchResult(null, ParsedLineScoreResult.empty("scoreboard-unavailable:" + providerGameId));
        }
    }

    private ParsedGameDetail applyScoreBoardStatusIfNeeded(
            Game game,
            String providerGameId,
            ParsedGameDetail parsedDetail,
            String lineScoreResponseBody
    ) {
        ParsedScoreBoardStatus scoreBoardStatus = kboGameDetailParser.parseScoreBoardStatus(providerGameId, lineScoreResponseBody)
                .orElseGet(() -> fetchScoreBoardPageStatus(game, providerGameId));
        if (scoreBoardStatus == null || !isInterruptedStatus(scoreBoardStatus.status())) {
            return parsedDetail;
        }
        if (parsedDetail.status() == GameStatus.CANCELLED || parsedDetail.status() == GameStatus.POSTPONED) {
            return parsedDetail;
        }
        if (parsedDetail.status() == GameStatus.FINAL && hasReliableFinalStatusReason(parsedDetail.statusReason())) {
            return parsedDetail;
        }
        ParsedScoreBoardStatus effectiveScoreBoardStatus = effectiveScoreBoardStatus(game, parsedDetail, scoreBoardStatus);
        log.info(
                "[GameDetailImport] scoreboard interruption applied publicGameId={} providerGameId={} previousStatus={} normalizedStatus={} statusReason={}",
                game.getPublicGameId(),
                providerGameId,
                parsedDetail.status(),
                effectiveScoreBoardStatus.status(),
                effectiveScoreBoardStatus.statusReason()
        );
        return parsedDetail.withScoreBoardStatus(effectiveScoreBoardStatus);
    }

    private ParsedScoreBoardStatus effectiveScoreBoardStatus(
            Game game,
            ParsedGameDetail parsedDetail,
            ParsedScoreBoardStatus scoreBoardStatus
    ) {
        if (scoreBoardStatus.status() == GameStatus.DELAYED
                && (game.getStatus() == GameStatus.LIVE || hasMeaningfulLiveState(parsedDetail))) {
            return new ParsedScoreBoardStatus(GameStatus.SUSPENDED, scoreBoardStatus.statusReason());
        }
        return scoreBoardStatus;
    }

    private ParsedScoreBoardStatus fetchScoreBoardPageStatus(Game game, String providerGameId) {
        try {
            String scoreBoardPage = kboGameDetailClient.fetchScoreBoardPage(providerGameId, game.getGameDate());
            return kboGameDetailParser.parseScoreBoardStatus(providerGameId, scoreBoardPage).orElse(null);
        } catch (RuntimeException exception) {
            log.info(
                    "[GameDetailImport] scoreboard page status unavailable publicGameId={} providerGameId={} reason={}",
                    game.getPublicGameId(),
                    providerGameId,
                    exception.getMessage()
            );
            return null;
        }
    }

    private boolean hasReliableFinalStatusReason(String statusReason) {
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

    private BoxscoreImportResult saveBoxscoreRecordsIfAvailable(
            Game game,
            String providerGameId,
            ParsedGameDetail parsedDetail,
            BoxscoreFetchResult boxscoreFetchResult
    ) {
        if (parsedDetail.status() != GameStatus.FINAL && !isLiveLike(parsedDetail.status())) {
            log.debug(
                    "[GameBoxscoreImport] skipped reason=nonLiveOrFinal gameId={} providerGameId={} status={}",
                    game.getPublicGameId(),
                    providerGameId,
                    parsedDetail.status()
            );
            return BoxscoreImportResult.skipped("nonLiveOrFinal");
        }
        String boxscoreResponseBody = boxscoreFetchResult.responseBody();
        if (boxscoreResponseBody == null || boxscoreResponseBody.isBlank()) {
            String reason = boxscoreFetchResult.skippedReason() == null ? "noBoxscore" : boxscoreFetchResult.skippedReason();
            log.info(
                    "[GameBoxscoreImport] skipped reason={} gameId={} providerGameId={} source={}",
                    reason,
                    game.getPublicGameId(),
                    providerGameId,
                    boxscoreFetchResult.source()
            );
            return BoxscoreImportResult.skipped(reason);
        }

        try {
            ParsedBoxscore parsedBoxscore = kboBoxscoreParser.parse(boxscoreResponseBody);
            int batterCount = parsedBoxscore.awayBatters().size() + parsedBoxscore.homeBatters().size();
            int pitcherCount = parsedBoxscore.awayPitchers().size() + parsedBoxscore.homePitchers().size();
            if (batterCount == 0 && pitcherCount == 0) {
                GameBoxscoreRecordService.GameBoxscoreRecordSaveResult result =
                        gameBoxscoreRecordService.saveBoxscoreRecords(game, parsedBoxscore);
                log.info(
                        "[GameBoxscoreImport] skipped reason=emptyRecords gameId={} providerGameId={} source={} parsedBatterCount=0 parsedPitcherCount=0 deletedBatters={} deletedPitchers={}",
                        game.getPublicGameId(),
                        providerGameId,
                        boxscoreFetchResult.source(),
                        result.deletedBatterCount(),
                        result.deletedPitcherCount()
                );
                return new BoxscoreImportResult(boxscoreFetchResult.source(), batterCount, pitcherCount, 0, 0, "emptyRecords");
            }

            GameBoxscoreRecordService.GameBoxscoreRecordSaveResult result =
                    gameBoxscoreRecordService.saveBoxscoreRecords(game, parsedBoxscore);
            log.info(
                    "[GameBoxscoreImport] gameId={} providerGameId={} source={} parsedBatterCount={} parsedPitcherCount={} savedBatterCount={} savedPitcherCount={} saved={}",
                    game.getPublicGameId(),
                    providerGameId,
                    boxscoreFetchResult.source(),
                    batterCount,
                    pitcherCount,
                    result.batterRecordCount(),
                    result.pitcherRecordCount(),
                    result.saved()
            );
            return new BoxscoreImportResult(
                    boxscoreFetchResult.source(),
                    batterCount,
                    pitcherCount,
                    result.batterRecordCount(),
                    result.pitcherRecordCount(),
                    null
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[GameBoxscoreImport] skipped reason=error gameId={} providerGameId={} error={}",
                    game.getPublicGameId(),
                    providerGameId,
                    exception.getMessage()
            );
            return BoxscoreImportResult.skipped("error:" + exception.getMessage());
        }
    }

    private void markDetailImportJob(
            UUID crawlJobId,
            boolean snapshotCreated,
            int importedLineScoreCount,
            ParsedGameDetail parsedDetail,
            BoxscoreImportResult boxscoreImportResult
    ) {
        if (parsedDetail.status() == GameStatus.FINAL && !boxscoreImportResult.hasSavedBoxscoreRecords()) {
            String message = "Final game boxscore records unavailable: source=%s parsedBatterCount=%d parsedPitcherCount=%d savedBatterCount=%d savedPitcherCount=%d reason=%s"
                    .formatted(
                            boxscoreImportResult.source(),
                            boxscoreImportResult.parsedBatterCount(),
                            boxscoreImportResult.parsedPitcherCount(),
                            boxscoreImportResult.savedBatterCount(),
                            boxscoreImportResult.savedPitcherCount(),
                            boxscoreImportResult.skippedReason()
                    );
            crawlJobTrackingService.markGameDetailPartialSuccess(
                    crawlJobId,
                    snapshotCreated,
                    importedLineScoreCount,
                    "boxscore",
                    message
            );
            log.warn("[GameBoxscoreImport] final detail partial_success reason={}", message);
            return;
        }
        crawlJobTrackingService.markGameDetailSucceeded(crawlJobId, snapshotCreated, importedLineScoreCount);
    }

    private String toLineupJson(ParsedLineupData lineupData) {
        if (lineupData == null || !lineupData.hasLineups()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "away", lineupData.away(),
                    "home", lineupData.home(),
                    "rawHash", lineupData.rawHash()
            ));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to serialize parsed lineup data", exception);
        }
    }

    private ResolvedOfficialDetail resolveOfficialDetail(Game game, String detailResponseBody) {
        List<ParsedGameDetail> parsedDetails = kboGameDetailParser.parseGameList(detailResponseBody);
        if (!isSyntheticProviderGameId(game.getProviderGameId())) {
            ParsedGameDetail exactMatch = parsedDetails.stream()
                    .filter(detail -> game.getProviderGameId().equals(detail.providerGameId()))
                    .findFirst()
                    .orElse(null);
            if (exactMatch != null) {
                return new ResolvedOfficialDetail(game.getProviderGameId(), exactMatch);
            }
        }

        String officialProviderGameId = resolveOfficialProviderGameIdByMatchup(game, detailResponseBody);
        ParsedGameDetail fallbackMatch = parsedDetails.stream()
                .filter(detail -> officialProviderGameId.equals(detail.providerGameId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Official detail row not found after matchup resolution for game " + game.getPublicGameId()
                                + " using providerGameId " + officialProviderGameId
                ));
        return new ResolvedOfficialDetail(officialProviderGameId, fallbackMatch);
    }

    private boolean isSyntheticProviderGameId(String providerGameId) {
        return providerGameId != null && providerGameId.startsWith("sched-");
    }

    private void logDetailParseFailure(Game game, DetailResponse detailResponse, String officialProviderGameId, Exception exception) {
        log.warn(
                "[GameDetailImport] detail parse failure publicGameId={} providerGameId={} officialProviderGameId={} method={} requestUrl={} status={} contentType={} responseType={} bodyLength={} reason={} bodyPreview={}",
                game.getPublicGameId(),
                game.getProviderGameId(),
                officialProviderGameId,
                detailResponse == null ? null : detailResponse.method(),
                detailResponse == null ? null : detailResponse.requestUri(),
                detailResponse == null ? null : detailResponse.statusCode(),
                detailResponse == null ? null : detailResponse.contentType(),
                detailResponse == null ? null : detailResponse.responseType(),
                detailResponse == null ? 0 : detailResponse.bodyLength(),
                exception.getMessage(),
                detailResponse == null ? null : detailResponse.bodyPreview(5000)
        );
    }

    private String resolveOfficialProviderGameIdByMatchup(Game game, String detailResponseBody) {
        try {
            List<JsonNode> rows = detailRows(detailResponseBody);
            String homeTeamCode = officialTeamCode(game.getHomeTeam().getTeamCode());
            String awayTeamCode = officialTeamCode(game.getAwayTeam().getTeamCode());
            String normalizedStadium = normalizeValue(game.getStadium());
            String scheduledTime = game.getScheduledAt() == null
                    ? null
                    : game.getScheduledAt().toLocalTime().format(OFFICIAL_TIME_FORMAT);

            List<JsonNode> teamMatches = rows.stream()
                    .filter(row -> homeTeamCode.equals(text(row, "HOME_ID")) && awayTeamCode.equals(text(row, "AWAY_ID")))
                    .toList();
            if (teamMatches.isEmpty()) {
                throw new IllegalStateException("Official detail row not found for matchup " + game.getPublicGameId());
            }
            if (teamMatches.size() == 1) {
                return providerGameId(teamMatches.get(0));
            }

            if (normalizedStadium != null) {
                JsonNode stadiumMatch = teamMatches.stream()
                        .filter(row -> normalizedStadium.equals(normalizeValue(text(row, "S_NM"))))
                        .findFirst()
                        .orElse(null);
                if (stadiumMatch != null) {
                    return providerGameId(stadiumMatch);
                }
            }

            if (scheduledTime != null) {
                JsonNode timeMatch = teamMatches.stream()
                        .filter(row -> scheduledTime.equals(text(row, "G_TM")))
                        .findFirst()
                        .orElse(null);
                if (timeMatch != null) {
                    return providerGameId(timeMatch);
                }
            }

            return providerGameId(teamMatches.get(0));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to parse official detail payload for matchup resolution", exception);
        }
    }

    private List<JsonNode> detailRows(String detailResponseBody) throws IOException {
        JsonNode root = objectMapper.readTree(detailResponseBody);
        JsonNode d = root.path("d");
        if (d.isTextual()) {
            String text = d.asText();
            if (text != null && (text.trim().startsWith("{") || text.trim().startsWith("["))) {
                root = objectMapper.readTree(text);
            }
        }
        for (String path : List.of(
                "game",
                "Game",
                "games",
                "rows",
                "list",
                "data.game",
                "data.games",
                "data.rows",
                "data.list"
        )) {
            JsonNode node = nodeAt(root, path);
            if (node.isArray()) {
                List<JsonNode> rows = new java.util.ArrayList<>();
                node.forEach(rows::add);
                return rows;
            }
        }
        if (root.isArray()) {
            List<JsonNode> rows = new java.util.ArrayList<>();
            root.forEach(rows::add);
            return rows;
        }
        return List.of();
    }

    private JsonNode nodeAt(JsonNode root, String dotPath) {
        JsonNode current = root;
        for (String segment : dotPath.split("\\.")) {
            current = current.path(segment);
            if (current.isMissingNode() || current.isNull()) {
                return current;
            }
        }
        return current;
    }

    private void backfillProviderGameIdIfNeeded(Game game, String providerGameId) {
        if (providerGameId.equals(game.getProviderGameId())) {
            return;
        }
        game.syncSchedule(
                game.getPublicGameId(),
                providerGameId,
                game.getGameDate(),
                game.getScheduledAt(),
                game.getStadium(),
                game.getStatus(),
                game.getHomeTeam(),
                game.getAwayTeam(),
                game.getHomeScore(),
                game.getAwayScore(),
                game.isCancelled(),
                game.isPostponed(),
                game.getCancelReason(),
                game.getRawCancelText(),
                game.getHomeStartingPitcherName(),
                game.getAwayStartingPitcherName(),
                game.getSourceUpdatedAt()
        );
    }

    private boolean persistSnapshotIfChanged(
            Game game,
            ParsedGameDetail parsedDetail,
            ParsedLineScoreResult lineScoreResult,
            SelectedScore selectedScore,
            String combinedRawHash,
            OffsetDateTime fetchedAt
    ) {
        if (!hasMeaningfulLiveState(parsedDetail) && !isLiveLike(parsedDetail.status())) {
            log.debug(
                    "snapshot persistence skipped empty non-live state game_id={} status={} raw_hash={} inning={} balls={} strikes={} outs={} runners={}/{}/{} current_pitcher_name={} current_batter_name={}",
                    game.getId(),
                    parsedDetail.status(),
                    combinedRawHash,
                    parsedDetail.inning(),
                    parsedDetail.balls(),
                    parsedDetail.strikes(),
                    parsedDetail.outs(),
                    parsedDetail.runnerOnFirst(),
                    parsedDetail.runnerOnSecond(),
                    parsedDetail.runnerOnThird(),
                    parsedDetail.currentPitcherName(),
                    parsedDetail.currentBatterName()
            );
            return false;
        }

        var latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId());
        GameSnapshot previousSnapshot = latestSnapshot.orElse(null);
        BaseRunnerNameResolver.ResolvedBaseRunners resolvedBaseRunners = baseRunnerNameResolver.resolve(
                previousSnapshot,
                parsedDetail
        );
        logBaseRunnerResolution(game, previousSnapshot, parsedDetail, resolvedBaseRunners);
        if (latestSnapshot.map(GameSnapshot::getRawHash).filter(combinedRawHash::equals).isPresent()
                && latestSnapshot.map(snapshot -> snapshotContentStateMatches(snapshot, parsedDetail, selectedScore, resolvedBaseRunners)).orElse(false)) {
            log.debug(
                    "snapshot persistence skipped unchanged game_id={} raw_hash={} current_pitcher_name={} current_batter_name={}",
                    game.getId(),
                    combinedRawHash,
                    parsedDetail.currentPitcherName(),
                    parsedDetail.currentBatterName()
            );
            return false;
        }

        GameSnapshot snapshot = new GameSnapshot(
                UUID.randomUUID(),
                game,
                parsedDetail.inning(),
                parsedDetail.inningHalf(),
                parsedDetail.inningLabel(),
                parsedDetail.balls(),
                parsedDetail.strikes(),
                parsedDetail.outs(),
                parsedDetail.runnerOnFirst(),
                parsedDetail.runnerOnSecond(),
                parsedDetail.runnerOnThird(),
                resolvedBaseRunners.firstBaseRunnerName(),
                resolvedBaseRunners.secondBaseRunnerName(),
                resolvedBaseRunners.thirdBaseRunnerName(),
                resolvedBaseRunners.firstBaseRunnerId(),
                resolvedBaseRunners.secondBaseRunnerId(),
                resolvedBaseRunners.thirdBaseRunnerId(),
                parsedDetail.currentPitcherName(),
                parsedDetail.currentBatterName(),
                selectedScore.homeScore(),
                selectedScore.awayScore(),
                lineScoreResult.homeTotals().hits(),
                lineScoreResult.awayTotals().hits(),
                lineScoreResult.homeTotals().errors(),
                lineScoreResult.awayTotals().errors(),
                lineScoreResult.homeTotals().balls(),
                lineScoreResult.awayTotals().balls(),
                combinedRawHash,
                parsedDetail.inningLabel(),
                parsedDetail.lastCompletedBatterName(),
                parsedDetail.lastCompletedPitcherName(),
                parsedDetail.lastCompletedPlayResult(),
                parsedDetail.lastCompletedPlayKey(),
                parsedDetail.sourceUpdatedAt(),
                fetchedAt
        );
        logLatestSnapshotSaveInputs(game, parsedDetail, resolvedBaseRunners, snapshot);
        gameSnapshotRepository.save(snapshot);
        log.debug(
                "snapshot persistence saved game_id={} raw_hash={} current_pitcher_name={} current_batter_name={}",
                game.getId(),
                combinedRawHash,
                snapshot.getCurrentPitcherName(),
                snapshot.getCurrentBatterName()
        );
        log.info(
                "[LiveSnapshot] game_id={} inning_label={} balls={} strikes={} outs={} occupancy={}/{}/{} baseRunnerNames first={} second={} third={} source={} current_pitcher_name={} current_batter_name={}",
                game.getId(),
                parsedDetail.inningLabel(),
                parsedDetail.balls(),
                parsedDetail.strikes(),
                parsedDetail.outs(),
                parsedDetail.runnerOnFirst(),
                parsedDetail.runnerOnSecond(),
                parsedDetail.runnerOnThird(),
                displayName(resolvedBaseRunners.firstBaseRunnerName()),
                displayName(resolvedBaseRunners.secondBaseRunnerName()),
                displayName(resolvedBaseRunners.thirdBaseRunnerName()),
                resolvedBaseRunners.source(),
                parsedDetail.currentPitcherName(),
                parsedDetail.currentBatterName()
        );
        return true;
    }

    private SelectedScore selectScore(
            Game game,
            ParsedGameDetail parsedDetail,
            ParsedLineScoreResult lineScoreResult
    ) {
        ScorePair oldScore = new ScorePair(game.getAwayScore(), game.getHomeScore());
        ScorePair detailScore = new ScorePair(parsedDetail.awayScore(), parsedDetail.homeScore());
        ScorePair lineScoreTotal = lineScoreTotal(lineScoreResult);

        if (detailScore.isComplete() && lineScoreTotal.isComplete() && !detailScore.equals(lineScoreTotal)) {
            SelectedScore selectedScore = lineScoreTotal.totalRuns() >= detailScore.totalRuns()
                    ? new SelectedScore(lineScoreTotal.awayScore(), lineScoreTotal.homeScore(), "scoreboard_line_score")
                    : new SelectedScore(detailScore.awayScore(), detailScore.homeScore(), "detail");
            log.info(
                    "[GameDetailImport] score source mismatch publicGameId={} providerGameId={} oldScore={} detailScore={} scoreboardScorePresent={} scoreboardScore={} selectedScore={} selectedScoreSource={}",
                    game.getPublicGameId(),
                    parsedDetail.providerGameId(),
                    oldScore.display(),
                    detailScore.display(),
                    true,
                    lineScoreTotal.display(),
                    selectedScore.display(),
                    selectedScore.source()
            );
            return selectedScore;
        }
        if (detailScore.isComplete()) {
            return new SelectedScore(detailScore.awayScore(), detailScore.homeScore(), "detail");
        }
        if (lineScoreTotal.isComplete()) {
            return new SelectedScore(lineScoreTotal.awayScore(), lineScoreTotal.homeScore(), "scoreboard_line_score");
        }

        SelectedScore selectedScore = new SelectedScore(oldScore.awayScore(), oldScore.homeScore(), "existing_game");
        log.info(
                "[GameDetailImport] score source fallback publicGameId={} providerGameId={} oldScore={} detailScore={} scoreboardScorePresent={} selectedScore={} selectedScoreSource={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                oldScore.display(),
                detailScore.display(),
                false,
                selectedScore.display(),
                selectedScore.source()
        );
        return selectedScore;
    }

    private ScorePair lineScoreTotal(ParsedLineScoreResult lineScoreResult) {
        if (lineScoreResult == null || lineScoreResult.awayTotals() == null || lineScoreResult.homeTotals() == null) {
            return ScorePair.empty();
        }
        return new ScorePair(lineScoreResult.awayTotals().runs(), lineScoreResult.homeTotals().runs());
    }

    private boolean hasMeaningfulLiveState(ParsedGameDetail parsedDetail) {
        return parsedDetail.inning() != null
                || parsedDetail.balls() != null
                || parsedDetail.strikes() != null
                || parsedDetail.outs() != null
                || parsedDetail.runnerOnFirst()
                || parsedDetail.runnerOnSecond()
                || parsedDetail.runnerOnThird()
                || clean(parsedDetail.firstBaseRunnerName()) != null
                || clean(parsedDetail.secondBaseRunnerName()) != null
                || clean(parsedDetail.thirdBaseRunnerName()) != null
                || clean(parsedDetail.currentPitcherName()) != null
                || clean(parsedDetail.currentBatterName()) != null;
    }

    private boolean isLiveLike(GameStatus status) {
        return status == GameStatus.LIVE || status == GameStatus.SUSPENDED;
    }

    private boolean isInterruptedStatus(GameStatus status) {
        return status == GameStatus.DELAYED || status == GameStatus.SUSPENDED;
    }

    private boolean snapshotContentStateMatches(
            GameSnapshot snapshot,
            ParsedGameDetail parsedDetail,
            SelectedScore selectedScore,
            BaseRunnerNameResolver.ResolvedBaseRunners resolvedBaseRunners
    ) {
        return Objects.equals(snapshot.getHomeScore(), selectedScore.homeScore())
                && Objects.equals(snapshot.getAwayScore(), selectedScore.awayScore())
                && snapshot.isRunnerOnFirst() == parsedDetail.runnerOnFirst()
                && snapshot.isRunnerOnSecond() == parsedDetail.runnerOnSecond()
                && snapshot.isRunnerOnThird() == parsedDetail.runnerOnThird()
                && Objects.equals(clean(snapshot.getCurrentPitcherName()), clean(parsedDetail.currentPitcherName()))
                && Objects.equals(clean(snapshot.getCurrentBatterName()), clean(parsedDetail.currentBatterName()))
                && Objects.equals(clean(snapshot.getFirstBaseRunnerName()), clean(resolvedBaseRunners.firstBaseRunnerName()))
                && Objects.equals(clean(snapshot.getSecondBaseRunnerName()), clean(resolvedBaseRunners.secondBaseRunnerName()))
                && Objects.equals(clean(snapshot.getThirdBaseRunnerName()), clean(resolvedBaseRunners.thirdBaseRunnerName()))
                && Objects.equals(clean(snapshot.getFirstBaseRunnerId()), clean(resolvedBaseRunners.firstBaseRunnerId()))
                && Objects.equals(clean(snapshot.getSecondBaseRunnerId()), clean(resolvedBaseRunners.secondBaseRunnerId()))
                && Objects.equals(clean(snapshot.getThirdBaseRunnerId()), clean(resolvedBaseRunners.thirdBaseRunnerId()))
                && Objects.equals(clean(snapshot.getLastCompletedBatterName()), clean(parsedDetail.lastCompletedBatterName()))
                && Objects.equals(clean(snapshot.getLastCompletedPitcherName()), clean(parsedDetail.lastCompletedPitcherName()))
                && Objects.equals(clean(snapshot.getLastCompletedPlayResult()), clean(parsedDetail.lastCompletedPlayResult()))
                && Objects.equals(clean(snapshot.getLastCompletedPlayKey()), clean(parsedDetail.lastCompletedPlayKey()));
    }

    private void logLatestSnapshotSaveInputs(
            Game game,
            ParsedGameDetail parsedDetail,
            BaseRunnerNameResolver.ResolvedBaseRunners resolvedBaseRunners,
            GameSnapshot snapshot
    ) {
        log.info(
                "[LiveSnapshot] raw base fields publicGameId={} providerGameId={} firstOrder={} secondOrder={} thirdOrder={} runner_on_first={} runner_on_second={} runner_on_third={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                parsedDetail.firstBaseBattingOrder(),
                parsedDetail.secondBaseBattingOrder(),
                parsedDetail.thirdBaseBattingOrder(),
                parsedDetail.runnerOnFirst(),
                parsedDetail.runnerOnSecond(),
                parsedDetail.runnerOnThird()
        );
        log.info(
                "[LiveSnapshot] raw runner ids publicGameId={} providerGameId={} first={} second={} third={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                displayName(parsedDetail.firstBaseRunnerId()),
                displayName(parsedDetail.secondBaseRunnerId()),
                displayName(parsedDetail.thirdBaseRunnerId())
        );
        log.info(
                "[LiveSnapshot] raw runner names publicGameId={} providerGameId={} first={} second={} third={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                displayName(parsedDetail.firstBaseRunnerName()),
                displayName(parsedDetail.secondBaseRunnerName()),
                displayName(parsedDetail.thirdBaseRunnerName())
        );
        log.info(
                "[LiveSnapshot] parsed base occupancy publicGameId={} providerGameId={} first={} second={} third={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                parsedDetail.runnerOnFirst(),
                parsedDetail.runnerOnSecond(),
                parsedDetail.runnerOnThird()
        );
        log.info(
                "[LiveSnapshot] parsed runner names publicGameId={} providerGameId={} first={} second={} third={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                displayName(parsedDetail.firstBaseRunnerName()),
                displayName(parsedDetail.secondBaseRunnerName()),
                displayName(parsedDetail.thirdBaseRunnerName())
        );
        log.info(
                "[LiveSnapshot] resolved runner source publicGameId={} providerGameId={} source={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                resolvedBaseRunners.source()
        );
        log.info(
                "[LiveSnapshot] saved runner_on_first/second/third publicGameId={} providerGameId={} first={} second={} third={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                snapshot.isRunnerOnFirst(),
                snapshot.isRunnerOnSecond(),
                snapshot.isRunnerOnThird()
        );
        log.info(
                "[LiveSnapshot] saved first/second/third_base_runner_name publicGameId={} providerGameId={} first={} second={} third={} current_batter_name={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                displayName(snapshot.getFirstBaseRunnerName()),
                displayName(snapshot.getSecondBaseRunnerName()),
                displayName(snapshot.getThirdBaseRunnerName()),
                displayName(snapshot.getCurrentBatterName())
        );
    }

    private String displayName(String value) {
        String cleaned = clean(value);
        return cleaned == null ? "<nil>" : cleaned;
    }

    private void logBaseRunnerResolution(
            Game game,
            GameSnapshot previous,
            ParsedGameDetail current,
            BaseRunnerNameResolver.ResolvedBaseRunners resolved
    ) {
        log.debug(
                "[BaseRunners] payload occupancy first={} second={} third={}",
                current.runnerOnFirst(),
                current.runnerOnSecond(),
                current.runnerOnThird()
        );
        log.debug(
                "[BaseRunners] payload names first={} second={} third={}",
                displayName(current.firstBaseRunnerName()),
                displayName(current.secondBaseRunnerName()),
                displayName(current.thirdBaseRunnerName())
        );
        log.debug(
                "[BaseRunners] resolved names first={} second={} third={} source={} game_id={} previous inning={}/{} current inning={}/{} previous occupancy={} current occupancy={}",
                displayName(resolved.firstBaseRunnerName()),
                displayName(resolved.secondBaseRunnerName()),
                displayName(resolved.thirdBaseRunnerName()),
                resolved.source(),
                game.getPublicGameId(),
                previous == null ? null : previous.getInning(),
                previous == null ? null : previous.getInningHalf(),
                current.inning(),
                current.inningHalf(),
                previous == null ? "---" : baseKey(previous),
                baseKey(current)
        );
        logUnresolvedBase("first", current.runnerOnFirst(), resolved.firstBaseRunnerName());
        logUnresolvedBase("second", current.runnerOnSecond(), resolved.secondBaseRunnerName());
        logUnresolvedBase("third", current.runnerOnThird(), resolved.thirdBaseRunnerName());
        if (previous != null) {
            logClearedBase("first", previous.isRunnerOnFirst(), current.runnerOnFirst());
            logClearedBase("second", previous.isRunnerOnSecond(), current.runnerOnSecond());
            logClearedBase("third", previous.isRunnerOnThird(), current.runnerOnThird());
        }
    }

    private void logUnresolvedBase(String base, boolean occupied, String resolvedName) {
        if (occupied && clean(resolvedName) == null) {
            log.warn("[BaseRunners] unresolved base={} reason=runnerOccupiedNameMissing", base);
        }
    }

    private void logClearedBase(String base, boolean previouslyOccupied, boolean occupied) {
        if (previouslyOccupied && !occupied) {
            log.debug("[BaseRunners] cleared base={} reason=baseEmpty", base);
        }
    }

    private String baseKey(GameSnapshot snapshot) {
        return "%s%s%s".formatted(snapshot.isRunnerOnFirst() ? "1" : "-", snapshot.isRunnerOnSecond() ? "2" : "-", snapshot.isRunnerOnThird() ? "3" : "-");
    }

    private String baseKey(ParsedGameDetail detail) {
        return "%s%s%s".formatted(detail.runnerOnFirst() ? "1" : "-", detail.runnerOnSecond() ? "2" : "-", detail.runnerOnThird() ? "3" : "-");
    }

    private void logCurrentPlayerDtoState(Game game, ParsedGameDetail parsedDetail) {
        if (!log.isDebugEnabled()) {
            return;
        }
        if (parsedDetail.currentPitcherName() == null || parsedDetail.currentBatterName() == null) {
            log.debug(
                    "current player parser dto missing public_game_id={} provider_game_id={} current_pitcher_name={} current_batter_name={}",
                    game.getPublicGameId(),
                    parsedDetail.providerGameId(),
                    parsedDetail.currentPitcherName(),
                    parsedDetail.currentBatterName()
            );
            return;
        }
        log.debug(
                "current player parser dto mapped public_game_id={} provider_game_id={} current_pitcher_name={} current_batter_name={}",
                game.getPublicGameId(),
                parsedDetail.providerGameId(),
                parsedDetail.currentPitcherName(),
                parsedDetail.currentBatterName()
        );
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean syncLineScoresIfChanged(Game game, List<KboLineScoreParser.ParsedLineScoreInning> innings) {
        List<LineScore> existingLineScores = lineScoreRepository.findByGame_IdOrderByInningNumberAsc(game.getId());
        if (lineScoresMatch(existingLineScores, innings)) {
            return false;
        }

        if (innings.isEmpty()) {
            lineScoreRepository.deleteAll(existingLineScores);
            lineScoreRepository.flush();
            return !existingLineScores.isEmpty();
        }

        Map<Integer, LineScore> existingByInning = existingLineScores.stream()
                .collect(Collectors.toMap(LineScore::getInningNumber, Function.identity(), (first, second) -> first));
        Set<Integer> incomingInnings = innings.stream()
                .map(KboLineScoreParser.ParsedLineScoreInning::inningNumber)
                .collect(Collectors.toSet());
        List<LineScore> staleLineScores = existingLineScores.stream()
                .filter(lineScore -> !incomingInnings.contains(lineScore.getInningNumber()))
                .toList();
        if (!staleLineScores.isEmpty()) {
            lineScoreRepository.deleteAll(staleLineScores);
        }

        lineScoreRepository.saveAll(innings.stream()
                .map(inning -> {
                    LineScore existing = existingByInning.get(inning.inningNumber());
                    if (existing != null) {
                        existing.updateRuns(inning.awayRuns(), inning.homeRuns());
                        return existing;
                    }
                    return new LineScore(
                            UUID.randomUUID(),
                            game,
                            inning.inningNumber(),
                            inning.awayRuns(),
                            inning.homeRuns()
                    );
                })
                .toList());
        return true;
    }

    private boolean lineScoresMatch(List<LineScore> existingLineScores, List<KboLineScoreParser.ParsedLineScoreInning> innings) {
        if (existingLineScores.size() != innings.size()) {
            return false;
        }
        for (int index = 0; index < existingLineScores.size(); index++) {
            LineScore existingLineScore = existingLineScores.get(index);
            KboLineScoreParser.ParsedLineScoreInning incomingLineScore = innings.get(index);
            if (existingLineScore.getInningNumber() != incomingLineScore.inningNumber()) {
                return false;
            }
            if (!java.util.Objects.equals(existingLineScore.getAwayRuns(), incomingLineScore.awayRuns())) {
                return false;
            }
            if (!java.util.Objects.equals(existingLineScore.getHomeRuns(), incomingLineScore.homeRuns())) {
                return false;
            }
        }
        return true;
    }

    private String officialTeamCode(String teamCode) {
        return OFFICIAL_TEAM_CODES_BY_TEAM_CODE.getOrDefault(teamCode, teamCode.toUpperCase());
    }

    private String text(JsonNode row, String fieldName) {
        JsonNode node = row.path(fieldName);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String providerGameId(JsonNode row) {
        String providerGameId = text(row, "G_ID");
        if (providerGameId == null) {
            throw new IllegalStateException("Official detail row is missing G_ID");
        }
        return providerGameId;
    }

    private String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replace("야구장", "")
                .replace("구장", "")
                .replace(" ", "")
                .trim()
                .toLowerCase();
        return normalized.isBlank() ? null : normalized;
    }

    private record ResolvedOfficialDetail(
            String providerGameId,
            ParsedGameDetail parsedDetail
    ) {
    }

    private record BoxscoreFetchResult(
            String responseBody,
            ParsedLineupData lineupData,
            String source,
            String skippedReason
    ) {
        private static BoxscoreFetchResult empty(String skippedReason) {
            return new BoxscoreFetchResult(null, ParsedLineupData.empty(null), "GetBoxScoreScroll", skippedReason);
        }
    }

    private record BoxscoreImportResult(
            String source,
            int parsedBatterCount,
            int parsedPitcherCount,
            int savedBatterCount,
            int savedPitcherCount,
            String skippedReason
    ) {
        private static BoxscoreImportResult skipped(String skippedReason) {
            return new BoxscoreImportResult("GetBoxScoreScroll", 0, 0, 0, 0, skippedReason);
        }

        private boolean hasSavedBoxscoreRecords() {
            return savedBatterCount > 0 && savedPitcherCount > 0;
        }
    }

    private record LiveTextImportResult(
            int batterRecordCount,
            int pitcherRecordCount,
            int eventCount,
            String skippedReason
    ) {
        private static LiveTextImportResult skipped(String skippedReason) {
            return new LiveTextImportResult(0, 0, 0, skippedReason);
        }
    }

    private record LiveTextFetchResult(
            KboLiveTextParser.ParsedLiveText parsedLiveText,
            String skippedReason
    ) {
        private static LiveTextFetchResult skipped(String skippedReason) {
            return new LiveTextFetchResult(null, skippedReason);
        }
    }

    private record ScorePair(Integer awayScore, Integer homeScore) {
        private static ScorePair empty() {
            return new ScorePair(null, null);
        }

        private boolean isComplete() {
            return awayScore != null && homeScore != null;
        }

        private int totalRuns() {
            return awayScore + homeScore;
        }

        private String display() {
            return awayScore == null || homeScore == null ? "<missing>" : awayScore + "-" + homeScore;
        }
    }

    private record SelectedScore(Integer awayScore, Integer homeScore, String source) {
        private String display() {
            return awayScore == null || homeScore == null ? "<missing>" : awayScore + "-" + homeScore;
        }
    }

    private record LineScoreFetchResult(
            String responseBody,
            ParsedLineScoreResult lineScoreResult
    ) {
    }
}
