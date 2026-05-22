package com.kbo.crawlerapi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.crawler.KboGameDetailClient.DetailResponse;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.parser.KboBoxscoreParser;
import com.kbo.crawlerapi.parser.KboBoxscoreParser.ParsedBoxscore;
import com.kbo.crawlerapi.parser.KboGameDetailParser;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedGameDetail;
import com.kbo.crawlerapi.parser.KboGameDetailParser.ParsedLineupData;
import com.kbo.crawlerapi.parser.KboLineScoreParser;
import com.kbo.crawlerapi.parser.KboLineScoreParser.ParsedLineScoreResult;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameDetailImportService {

    private static final Logger log = LoggerFactory.getLogger(GameDetailImportService.class);
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
    private final GameBoxscoreRecordService gameBoxscoreRecordService;
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
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.kboGameDetailClient = kboGameDetailClient;
        this.kboGameDetailParser = kboGameDetailParser;
        this.kboLineScoreParser = kboLineScoreParser;
        this.kboBoxscoreParser = kboBoxscoreParser;
        this.gameBoxscoreRecordService = gameBoxscoreRecordService;
        this.crawlJobTrackingService = crawlJobTrackingService;
        this.baseRunnerNameResolver = baseRunnerNameResolver;
        this.objectMapper = new ObjectMapper();
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
                    .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + publicGameId));
            if (game.getProviderGameId() == null || game.getProviderGameId().isBlank()) {
                throw new IllegalStateException("Provider game ID is not available yet for game " + publicGameId);
            }
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
            lineScoreResponseBody = kboGameDetailClient.fetchScoreBoard(
                    resolvedOfficialDetail.providerGameId(),
                    game.getGameDate().getYear()
            );
            lineScoreResult = kboLineScoreParser.parse(lineScoreResponseBody);
            boxscoreFetchResult = fetchBoxscoreIfLineupAvailable(resolvedOfficialDetail.providerGameId(), game.getGameDate().getYear(), parsedDetail);
            lineupData = boxscoreFetchResult.lineupData();
            parsedDetail = kboGameDetailParser.applyOfficialRunnerNamesFromLineup(parsedDetail, lineupData);
        } catch (Exception exception) {
            logDetailParseFailure(game, detailResponse, officialProviderGameId, exception);
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            backfillProviderGameIdIfNeeded(game, resolvedOfficialDetail.providerGameId());
            OffsetDateTime fetchedAt = OffsetDateTime.now();
            String combinedRawHash = hash(parsedDetail.rawHash() + ":" + lineScoreResult.rawHash());
            boolean snapshotCreated = persistSnapshotIfChanged(game, parsedDetail, lineScoreResult, combinedRawHash, fetchedAt);
            boolean lineScoresUpdated = syncLineScoresIfChanged(game, lineScoreResult.innings());
            game.syncDetail(
                    parsedDetail.status(),
                    parsedDetail.homeScore(),
                    parsedDetail.awayScore(),
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
            saveBoxscoreRecordsIfAvailable(game, resolvedOfficialDetail.providerGameId(), parsedDetail, boxscoreFetchResult.responseBody());
            crawlJobTrackingService.markGameDetailSucceeded(crawlJob.getId(), snapshotCreated, lineScoreResult.innings().size());

            return new GameDetailImportResult(
                    game.getPublicGameId(),
                    game.getProviderGameId(),
                    game.getGameDate(),
                    parsedDetail.status().getApiValue(),
                    snapshotCreated,
                    lineScoresUpdated,
                    lineScoreResult.innings().size(),
                    parsedDetail.awayScore(),
                    parsedDetail.homeScore(),
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

    private BoxscoreFetchResult fetchBoxscoreIfLineupAvailable(String providerGameId, int seasonId, ParsedGameDetail parsedDetail) {
        if (!parsedDetail.lineupAvailable()) {
            return BoxscoreFetchResult.empty();
        }
        try {
            String responseBody = kboGameDetailClient.fetchBoxScore(providerGameId, seasonId);
            return new BoxscoreFetchResult(responseBody, kboGameDetailParser.parseLineupData(responseBody));
        } catch (RuntimeException exception) {
            return BoxscoreFetchResult.empty();
        }
    }

    private void saveBoxscoreRecordsIfAvailable(
            Game game,
            String providerGameId,
            ParsedGameDetail parsedDetail,
            String boxscoreResponseBody
    ) {
        if (parsedDetail.status() != GameStatus.FINAL) {
            log.debug(
                    "[GameBoxscoreImport] skipped reason=nonFinal gameId={} providerGameId={} status={}",
                    game.getPublicGameId(),
                    providerGameId,
                    parsedDetail.status()
            );
            return;
        }
        if (boxscoreResponseBody == null || boxscoreResponseBody.isBlank()) {
            log.info("[GameBoxscoreImport] skipped reason=noBoxscore gameId={} providerGameId={}", game.getPublicGameId(), providerGameId);
            return;
        }

        try {
            ParsedBoxscore parsedBoxscore = kboBoxscoreParser.parse(boxscoreResponseBody);
            int batterCount = parsedBoxscore.awayBatters().size() + parsedBoxscore.homeBatters().size();
            int pitcherCount = parsedBoxscore.awayPitchers().size() + parsedBoxscore.homePitchers().size();
            if (batterCount == 0 && pitcherCount == 0) {
                log.info("[GameBoxscoreImport] skipped reason=emptyRecords gameId={} providerGameId={}", game.getPublicGameId(), providerGameId);
                return;
            }

            GameBoxscoreRecordService.GameBoxscoreRecordSaveResult result =
                    gameBoxscoreRecordService.saveBoxscoreRecords(game, parsedBoxscore);
            log.info(
                    "[GameBoxscoreImport] gameId={} providerGameId={} batters={} pitchers={} saved={}",
                    game.getPublicGameId(),
                    providerGameId,
                    result.batterRecordCount(),
                    result.pitcherRecordCount(),
                    result.saved()
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "[GameBoxscoreImport] skipped reason=error gameId={} providerGameId={} error={}",
                    game.getPublicGameId(),
                    providerGameId,
                    exception.getMessage()
            );
        }
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
                game.getSourceUpdatedAt()
        );
    }

    private boolean persistSnapshotIfChanged(
            Game game,
            ParsedGameDetail parsedDetail,
            ParsedLineScoreResult lineScoreResult,
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
                && latestSnapshot.map(snapshot -> snapshotCurrentPlayersMatch(snapshot, parsedDetail, resolvedBaseRunners)).orElse(false)) {
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
                parsedDetail.homeScore(),
                parsedDetail.awayScore(),
                lineScoreResult.homeTotals().hits(),
                lineScoreResult.awayTotals().hits(),
                lineScoreResult.homeTotals().errors(),
                lineScoreResult.awayTotals().errors(),
                lineScoreResult.homeTotals().balls(),
                lineScoreResult.awayTotals().balls(),
                combinedRawHash,
                null,
                null,
                null,
                null,
                null,
                parsedDetail.sourceUpdatedAt(),
                fetchedAt
        );
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

    private boolean snapshotCurrentPlayersMatch(
            GameSnapshot snapshot,
            ParsedGameDetail parsedDetail,
            BaseRunnerNameResolver.ResolvedBaseRunners resolvedBaseRunners
    ) {
        return Objects.equals(clean(snapshot.getCurrentPitcherName()), clean(parsedDetail.currentPitcherName()))
                && Objects.equals(clean(snapshot.getCurrentBatterName()), clean(parsedDetail.currentBatterName()))
                && Objects.equals(clean(snapshot.getFirstBaseRunnerName()), clean(resolvedBaseRunners.firstBaseRunnerName()))
                && Objects.equals(clean(snapshot.getSecondBaseRunnerName()), clean(resolvedBaseRunners.secondBaseRunnerName()))
                && Objects.equals(clean(snapshot.getThirdBaseRunnerName()), clean(resolvedBaseRunners.thirdBaseRunnerName()));
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
                "[BaseRunners] resolution game_id={} previous inning={}/{} current inning={}/{} previous occupancy={} current occupancy={} previous names first={} second={} third={} resolved names first={} second={} third={} source={}",
                game.getPublicGameId(),
                previous == null ? null : previous.getInning(),
                previous == null ? null : previous.getInningHalf(),
                current.inning(),
                current.inningHalf(),
                previous == null ? "---" : baseKey(previous),
                baseKey(current),
                displayName(previous == null ? null : previous.getFirstBaseRunnerName()),
                displayName(previous == null ? null : previous.getSecondBaseRunnerName()),
                displayName(previous == null ? null : previous.getThirdBaseRunnerName()),
                displayName(resolved.firstBaseRunnerName()),
                displayName(resolved.secondBaseRunnerName()),
                displayName(resolved.thirdBaseRunnerName()),
                resolved.source()
        );
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

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte current : hash) {
                builder.append(String.format("%02x", current));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
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
            ParsedLineupData lineupData
    ) {
        private static BoxscoreFetchResult empty() {
            return new BoxscoreFetchResult(null, ParsedLineupData.empty(null));
        }
    }
}
