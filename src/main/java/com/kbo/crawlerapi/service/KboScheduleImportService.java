package com.kbo.crawlerapi.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.crawler.KboScheduleClient;
import com.kbo.crawlerapi.crawler.KboScheduleClient.ScheduleResponse;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboGameListParser;
import com.kbo.crawlerapi.parser.KboGameListParser.ParsedGameListGame;
import com.kbo.crawlerapi.parser.KboScheduleParser;
import com.kbo.crawlerapi.parser.KboScheduleParser.MonthlyScheduleParseResult;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.ScheduleGameWriteRepository;
import com.kbo.crawlerapi.repository.TeamRepository;
import com.kbo.crawlerapi.support.TeamCatalog;
import com.kbo.crawlerapi.support.TeamCatalog.TeamDefinition;

@Service
public class KboScheduleImportService {

    private static final Logger log = LoggerFactory.getLogger(KboScheduleImportService.class);
    private static final DateTimeFormatter PUBLIC_GAME_ID_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final Pattern SCORE_DIGIT_PATTERN = Pattern.compile("\\d+");

    private final KboScheduleClient kboScheduleClient;
    private final KboScheduleParser kboScheduleParser;
    private final KboGameDetailClient kboGameDetailClient;
    private final KboGameListParser kboGameListParser;
    private final TeamRepository teamRepository;
    private final GameRepository gameRepository;
    private final ScheduleGameWriteRepository scheduleGameWriteRepository;
    private final CrawlJobTrackingService crawlJobTrackingService;
    private final Clock applicationClock;

    @Autowired
    public KboScheduleImportService(
            KboScheduleClient kboScheduleClient,
            KboScheduleParser kboScheduleParser,
            KboGameDetailClient kboGameDetailClient,
            KboGameListParser kboGameListParser,
            TeamRepository teamRepository,
            GameRepository gameRepository,
            ScheduleGameWriteRepository scheduleGameWriteRepository,
            CrawlJobTrackingService crawlJobTrackingService,
            Clock applicationClock
    ) {
        this.kboScheduleClient = kboScheduleClient;
        this.kboScheduleParser = kboScheduleParser;
        this.kboGameDetailClient = kboGameDetailClient;
        this.kboGameListParser = kboGameListParser;
        this.teamRepository = teamRepository;
        this.gameRepository = gameRepository;
        this.scheduleGameWriteRepository = scheduleGameWriteRepository;
        this.crawlJobTrackingService = crawlJobTrackingService;
        this.applicationClock = applicationClock;
    }

    protected KboScheduleImportService(
            KboScheduleClient kboScheduleClient,
            KboScheduleParser kboScheduleParser,
            TeamRepository teamRepository,
            ScheduleGameWriteRepository scheduleGameWriteRepository,
            CrawlJobTrackingService crawlJobTrackingService,
            Clock applicationClock
    ) {
        this(kboScheduleClient, kboScheduleParser, null, null, teamRepository, null, scheduleGameWriteRepository, crawlJobTrackingService, applicationClock);
    }

    protected KboScheduleImportService(
            KboScheduleClient kboScheduleClient,
            KboScheduleParser kboScheduleParser,
            KboGameDetailClient kboGameDetailClient,
            KboGameListParser kboGameListParser,
            TeamRepository teamRepository,
            ScheduleGameWriteRepository scheduleGameWriteRepository,
            CrawlJobTrackingService crawlJobTrackingService,
            Clock applicationClock
    ) {
        this(kboScheduleClient, kboScheduleParser, kboGameDetailClient, kboGameListParser, teamRepository, null, scheduleGameWriteRepository, crawlJobTrackingService, applicationClock);
    }

    public ScheduleIngestionResult importMonthlySchedule(YearMonth yearMonth) {
        String targetKey = yearMonth.toString();
        var crawlJob = crawlJobTrackingService.createRunningScheduleImportJob(targetKey);
        ScheduleResponse response;
        MonthlyScheduleParseResult parseResult;

        try {
            response = kboScheduleClient.fetchMonthlyScheduleResponse(yearMonth);
            KboScheduleClient.validateScheduleResponse(response);
            logScheduleResponseDiagnostics("monthly", null, yearMonth, response);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "request", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            parseResult = kboScheduleParser.parseMonthlyScheduleResult(response.body(), yearMonth);
        } catch (Exception exception) {
            logScheduleParseFailure("monthly", null, yearMonth, response, exception);
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        for (var skippedRow : parseResult.skippedRows()) {
            logSkippedScheduleRowDuringImport(skippedRow);
        }

        try {
            PersistResult persistResult = persistParsedGames(
                    suppressPrematureLiveStatuses(parseResult.games()),
                    parseResult.skippedRows()
            );
            crawlJobTrackingService.markScheduleSucceeded(crawlJob.getId(), parseResult.skippedRows().size());
            return new ScheduleIngestionResult(
                    yearMonth,
                    persistResult.teamCreatedCount(),
                    persistResult.teamUpdatedCount(),
                    persistResult.gameCreatedCount(),
                    persistResult.gameUpdatedCount(),
                    parseResult.skippedRows().size(),
                    parseResult.skippedMissingProviderGameIdCount(),
                    0,
                    parseResult.skippedRows()
            );
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(
                    crawlJob.getId(),
                    "persist",
                    exception.getMessage(),
                    exception,
                    parseResult.skippedRows().size()
            );
            throw exception;
        }
    }

    public DayScheduleIngestionResult crawlDay(LocalDate date) {
        Objects.requireNonNull(date, "date must not be null");

        YearMonth yearMonth = YearMonth.from(date);
        var crawlJob = crawlJobTrackingService.createRunningDailyScheduleImportJob(date.toString());
        ScheduleResponse response;
        MonthlyScheduleParseResult parseResult;

        log.info("Starting daily KBO schedule crawl. requestedDate={}, derivedMonth={}", date, yearMonth);

        try {
            response = kboScheduleClient.fetchMonthlyScheduleResponse(yearMonth);
            KboScheduleClient.validateScheduleResponse(response);
            logScheduleResponseDiagnostics("daily", date, yearMonth, response);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "request", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            parseResult = kboScheduleParser.parseMonthlyScheduleResult(response.body(), yearMonth);
        } catch (Exception exception) {
            logScheduleParseFailure("daily", date, yearMonth, response, exception);
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        List<ParsedScheduleGame> gamesForDate = parseResult.games().stream()
                .filter(parsedGame -> date.equals(parsedGame.gameDate()))
                .toList();
        List<KboScheduleParser.SkippedScheduleRow> skippedRowsForDate = parseResult.skippedRows().stream()
                .filter(skippedRow -> date.equals(skippedRow.gameDate()))
                .toList();
        gamesForDate = enrichDailyScheduleGames(date, gamesForDate);
        gamesForDate = suppressPrematureLiveStatuses(gamesForDate);

        log.info(
                "Filtered daily KBO schedule crawl rows. requestedDate={}, derivedMonth={}, fetchedRowCount={}, parsedGameCount={}, filteredGameCount={}, filteredSkippedRowCount={}",
                date,
                yearMonth,
                parseResult.fetchedRowCount(),
                parseResult.games().size(),
                gamesForDate.size(),
                skippedRowsForDate.size()
        );

        for (var skippedRow : skippedRowsForDate) {
            logSkippedScheduleRowDuringImport(skippedRow);
        }

        try {
            PersistResult persistResult = persistParsedGames(gamesForDate, skippedRowsForDate);
            crawlJobTrackingService.markScheduleSucceeded(crawlJob.getId(), skippedRowsForDate.size());
            log.info(
                    "Completed daily KBO schedule crawl. requestedDate={}, derivedMonth={}, gameCreatedCount={}, gameUpdatedCount={}, skippedRowCount={}, skippedMissingProviderGameIdCount={}",
                    date,
                    yearMonth,
                    persistResult.gameCreatedCount(),
                    persistResult.gameUpdatedCount(),
                    skippedRowsForDate.size(),
                    skippedMissingProviderGameIdCount(skippedRowsForDate)
            );
            return new DayScheduleIngestionResult(
                    date,
                    persistResult.teamCreatedCount(),
                    persistResult.teamUpdatedCount(),
                    gamesForDate.size(),
                    persistResult.gameCreatedCount(),
                    persistResult.gameUpdatedCount(),
                    skippedRowsForDate.size(),
                    skippedMissingProviderGameIdCount(skippedRowsForDate),
                    0,
                    skippedRowsForDate
            );
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(
                    crawlJob.getId(),
                    "persist",
                    exception.getMessage(),
                    exception,
                    skippedRowsForDate.size()
            );
            throw exception;
        }
    }

    public MonthScheduleIngestionResult crawlMonth(YearMonth yearMonth) {
        Objects.requireNonNull(yearMonth, "yearMonth must not be null");

        LocalDate from = yearMonth.atDay(1);
        LocalDate to = yearMonth.atEndOfMonth();
        List<MonthScheduleIngestionResult.DailyResult> dailyResults = new ArrayList<>();
        List<MonthScheduleIngestionResult.Failure> failures = new ArrayList<>();
        int successDays = 0;
        int failedDays = 0;
        int createdCount = 0;
        int updatedCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        log.info("Starting monthly KBO schedule crawl. yearMonth={}, from={}, to={}", yearMonth, from, to);

        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            try {
                DayScheduleIngestionResult result = crawlDay(date);
                successDays++;
                createdCount += result.gameCreatedCount();
                updatedCount += result.gameUpdatedCount();
                skippedCount += result.skippedRowCount();
                failureCount += result.failureCount();
                dailyResults.add(new MonthScheduleIngestionResult.DailyResult(
                        date,
                        result.gameCreatedCount(),
                        result.gameUpdatedCount(),
                        result.skippedRowCount(),
                        result.failureCount(),
                        MonthScheduleIngestionResult.DailyStatus.SUCCESS
                ));
            } catch (Exception exception) {
                failedDays++;
                failureCount++;
                String message = exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage();

                log.warn(
                        "Daily KBO schedule crawl failed during monthly crawl. requestedDate={}, yearMonth={}, message={}",
                        date,
                        yearMonth,
                        message,
                        exception
                );

                failures.add(new MonthScheduleIngestionResult.Failure(date, message));
                dailyResults.add(new MonthScheduleIngestionResult.DailyResult(
                        date,
                        0,
                        0,
                        0,
                        1,
                        MonthScheduleIngestionResult.DailyStatus.FAILED
                ));
            }
        }

        log.info(
                "Completed monthly KBO schedule crawl. yearMonth={}, totalDays={}, successDays={}, failedDays={}, gameCreatedCount={}, gameUpdatedCount={}, skippedRowCount={}, failureCount={}",
                yearMonth,
                dailyResults.size(),
                successDays,
                failedDays,
                createdCount,
                updatedCount,
                skippedCount,
                failureCount
        );

        return new MonthScheduleIngestionResult(
                yearMonth,
                from,
                to,
                dailyResults.size(),
                successDays,
                failedDays,
                createdCount,
                updatedCount,
                skippedCount,
                failureCount,
                failures,
                dailyResults
        );
    }

    private int skippedMissingProviderGameIdCount(List<KboScheduleParser.SkippedScheduleRow> skippedRows) {
        return (int) skippedRows.stream()
                .filter(skippedRow -> "MISSING_PROVIDER_GAME_ID".equals(skippedRow.reason()))
                .count();
    }

    private List<ParsedScheduleGame> suppressPrematureLiveStatuses(List<ParsedScheduleGame> games) {
        return games.stream()
                .map(this::suppressPrematureLiveStatus)
                .toList();
    }

    private ParsedScheduleGame suppressPrematureLiveStatus(ParsedScheduleGame game) {
        if (game.status() != GameStatus.LIVE || game.scheduledAt() == null) {
            return game;
        }
        if (!OffsetDateTime.now(applicationClock).toInstant().isBefore(game.scheduledAt().toInstant())) {
            return game;
        }
        log.info(
                "Suppressed premature LIVE schedule status. providerGameId={} gameDate={} scheduledAt={} now={}",
                game.providerGameId(),
                game.gameDate(),
                game.scheduledAt(),
                OffsetDateTime.now(applicationClock)
        );
        return game.withStatus(GameStatus.SCHEDULED);
    }

    private void logSkippedScheduleRowDuringImport(KboScheduleParser.SkippedScheduleRow skippedRow) {
        if ("MISSING_PROVIDER_GAME_ID".equals(skippedRow.reason())) {
            GameStatus status = fallbackStatus(skippedRow.note());
            if (isCancellationTarget(status)) {
                log.info(
                        "Schedule row without providerGameId requires fallback matching. date={}, playText={}, note={}, status={}",
                        skippedRow.gameDate(),
                        skippedRow.playText(),
                        skippedRow.note(),
                        status
                );
                return;
            }
            log.debug(
                    "Skipped non-critical schedule row without providerGameId. date={}, playText={}, note={}",
                    skippedRow.gameDate(),
                    skippedRow.playText(),
                    skippedRow.note()
            );
            return;
        }

        log.warn(
                "Skipped malformed schedule row during import. date={}, reason={}, playText={}, note={}",
                skippedRow.gameDate(),
                skippedRow.reason(),
                skippedRow.playText(),
                skippedRow.note()
        );
    }

    private void logScheduleResponseDiagnostics(String mode, LocalDate requestedDate, YearMonth yearMonth, ScheduleResponse response) {
        log.info(
                "Fetched KBO schedule response. mode={}, requestedDate={}, yearMonth={}, method={}, uri={}, status={}, contentType={}, bodyLength={}, redirectLocation={}",
                mode,
                requestedDate,
                yearMonth,
                response.method(),
                response.requestUri(),
                response.statusCode(),
                response.contentType(),
                response.bodyLength(),
                response.redirectLocation()
        );
    }

    private void logScheduleParseFailure(
            String mode,
            LocalDate requestedDate,
            YearMonth yearMonth,
            ScheduleResponse response,
            Exception exception
    ) {
        log.warn(
                "Failed to parse KBO schedule response. mode={}, requestedDate={}, yearMonth={}, method={}, uri={}, status={}, contentType={}, bodyLength={}, redirectLocation={}, reason={}, bodyPreview={}",
                mode,
                requestedDate,
                yearMonth,
                response.method(),
                response.requestUri(),
                response.statusCode(),
                response.contentType(),
                response.bodyLength(),
                response.redirectLocation(),
                exception.getMessage(),
                response.bodyPreview(5000)
        );
    }

    private List<ParsedScheduleGame> enrichDailyScheduleGames(LocalDate date, List<ParsedScheduleGame> gamesForDate) {
        if (kboGameDetailClient == null || kboGameListParser == null || gamesForDate.isEmpty()) {
            return gamesForDate;
        }

        List<ParsedGameListGame> gameListGames;
        try {
            var response = kboGameDetailClient.fetchGameListResponse(date);
            KboGameDetailClient.validateDetailResponse(response, "KBO game list endpoint returned error page");
            gameListGames = kboGameListParser.parseGameList(response.body());
        } catch (Exception exception) {
            log.warn("KBO GameList starter enrichment failed. date={}, reason={}", date, exception.getMessage(), exception);
            return gamesForDate;
        }

        Map<String, ParsedGameListGame> byProviderGameId = new HashMap<>();
        Map<String, ParsedGameListGame> byNaturalKey = new HashMap<>();
        for (ParsedGameListGame gameListGame : gameListGames) {
            byProviderGameId.put(gameListGame.providerGameId(), gameListGame);
            if (!gameListGame.naturalKey().equals("|")) {
                byNaturalKey.put(gameListGame.naturalKey(), gameListGame);
            }
            log.info(
                    "KBO GameList starter parsed. date={}, providerGameId={}, awayStartingPitcherName={}, homeStartingPitcherName={}",
                    date,
                    gameListGame.providerGameId(),
                    gameListGame.awayStartingPitcherName(),
                    gameListGame.homeStartingPitcherName()
            );
        }

        List<ParsedScheduleGame> enrichedGames = new ArrayList<>();
        for (ParsedScheduleGame parsedGame : gamesForDate) {
            ParsedGameListGame gameListGame = null;
            String resolvedBy = null;
            if (hasText(parsedGame.providerGameId())) {
                gameListGame = byProviderGameId.get(parsedGame.providerGameId());
                resolvedBy = gameListGame == null ? null : "providerGameId";
            }
            if (gameListGame == null) {
                String naturalKey = ParsedGameListGame.naturalKey(parsedGame.awayProviderTeamName(), parsedGame.homeProviderTeamName());
                gameListGame = byNaturalKey.get(naturalKey);
                resolvedBy = gameListGame == null ? null : "teams";
            }

            if (gameListGame == null) {
                log.debug(
                        "KBO GameList starter enrichment skipped. date={}, providerGameId={}, away={}, home={}, reason=noMatch",
                        date,
                        parsedGame.providerGameId(),
                        parsedGame.awayProviderTeamName(),
                        parsedGame.homeProviderTeamName()
                );
                enrichedGames.add(parsedGame);
                continue;
            }

            ParsedScheduleGame enrichedGame = parsedGame.withGameListStarterNames(gameListGame);
            log.info(
                    "KBO GameList starter enrichment matched. date={}, providerGameId={}, resolvedBy={}, awayStartingPitcherName={}, homeStartingPitcherName={}",
                    date,
                    enrichedGame.providerGameId(),
                    resolvedBy,
                    enrichedGame.awayStartingPitcherName(),
                    enrichedGame.homeStartingPitcherName()
            );
            enrichedGames.add(enrichedGame);
        }
        return enrichedGames;
    }

    protected PersistResult persistParsedGames(java.util.List<ParsedScheduleGame> parsedGames, List<KboScheduleParser.SkippedScheduleRow> skippedRows) {
        OffsetDateTime appliedAt = OffsetDateTime.now(applicationClock);
        int teamCreatedCount = 0;
        int teamUpdatedCount = 0;
        int gameCreatedCount = 0;
        int gameUpdatedCount = 0;

        for (ParsedScheduleGame parsedGame : parsedGames) {
            TeamUpsertResult awayTeamResult = upsertTeam(parsedGame.awayProviderTeamName());
            TeamUpsertResult homeTeamResult = upsertTeam(parsedGame.homeProviderTeamName());
            String publicGameId = buildPublicGameId(parsedGame, homeTeamResult.team(), awayTeamResult.team());

            teamCreatedCount += awayTeamResult.created() ? 1 : 0;
            teamCreatedCount += homeTeamResult.created() ? 1 : 0;
            teamUpdatedCount += awayTeamResult.updated() ? 1 : 0;
            teamUpdatedCount += homeTeamResult.updated() ? 1 : 0;

            ScheduleGameWriteRepository.GameWriteResult gameResult = scheduleGameWriteRepository.upsertScheduleGame(
                    parsedGame,
                    awayTeamResult.team(),
                    homeTeamResult.team(),
                    publicGameId,
                    sourceUpdatedAtForApply(parsedGame, appliedAt)
            );
            logStarterPersistence(parsedGame, gameResult);
            gameCreatedCount += gameResult.created() ? 1 : 0;
            gameUpdatedCount += gameResult.updated() ? 1 : 0;
        }

        gameUpdatedCount += applyMissingProviderCancellationFallbacks(skippedRows, appliedAt);
        return new PersistResult(teamCreatedCount, teamUpdatedCount, gameCreatedCount, gameUpdatedCount);
    }

    private void logStarterPersistence(ParsedScheduleGame parsedGame, ScheduleGameWriteRepository.GameWriteResult gameResult) {
        if (!hasText(parsedGame.awayStartingPitcherName()) && !hasText(parsedGame.homeStartingPitcherName())) {
            log.debug(
                    "KBO starter persistence skipped. date={}, providerGameId={}, reason=noStarterNames",
                    parsedGame.gameDate(),
                    parsedGame.providerGameId()
            );
            return;
        }
        if (gameResult.created() || gameResult.updated()) {
            log.info(
                    "KBO starter persistence applied. date={}, providerGameId={}, awayStartingPitcherName={}, homeStartingPitcherName={}, persisted={}",
                    parsedGame.gameDate(),
                    parsedGame.providerGameId(),
                    parsedGame.awayStartingPitcherName(),
                    parsedGame.homeStartingPitcherName(),
                    gameResult.created() ? "created" : "updated"
            );
            return;
        }
        log.info(
                "KBO starter persistence skipped. date={}, providerGameId={}, awayStartingPitcherName={}, homeStartingPitcherName={}, reason=unchangedOrExistingValue",
                parsedGame.gameDate(),
                parsedGame.providerGameId(),
                parsedGame.awayStartingPitcherName(),
                parsedGame.homeStartingPitcherName()
        );
    }

    private int applyMissingProviderCancellationFallbacks(List<KboScheduleParser.SkippedScheduleRow> skippedRows, OffsetDateTime appliedAt) {
        if (gameRepository == null || skippedRows == null || skippedRows.isEmpty()) {
            return 0;
        }

        int updatedCount = 0;
        for (KboScheduleParser.SkippedScheduleRow skippedRow : skippedRows) {
            if (!"MISSING_PROVIDER_GAME_ID".equals(skippedRow.reason())) {
                continue;
            }
            GameStatus status = fallbackStatus(skippedRow.note());
            if (!isCancellationTarget(status)) {
                log.debug(
                        "Skipped non-critical schedule row without providerGameId. date={}, playText={}, note={}",
                        skippedRow.gameDate(),
                        skippedRow.playText(),
                        skippedRow.note()
                );
                continue;
            }

            FallbackMatchTeams matchTeams = parseFallbackTeams(skippedRow.playText());
            if (matchTeams == null) {
                log.warn(
                        "Could not parse cancellation fallback teams. date={}, playText={}, note={}",
                        skippedRow.gameDate(),
                        skippedRow.playText(),
                        skippedRow.note()
                );
                continue;
            }

            Optional<Team> awayTeam = teamRepository.findByTeamCode(matchTeams.awayTeamCode());
            Optional<Team> homeTeam = teamRepository.findByTeamCode(matchTeams.homeTeamCode());
            if (awayTeam.isEmpty() || homeTeam.isEmpty()) {
                log.warn(
                        "Could not resolve cancellation fallback teams. date={}, playText={}, note={}, normalizedAway={}, normalizedHome={}",
                        skippedRow.gameDate(),
                        skippedRow.playText(),
                        skippedRow.note(),
                        matchTeams.awayTeamCode(),
                        matchTeams.homeTeamCode()
                );
                continue;
            }

            Optional<Game> existingGame = gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                    "kbo",
                    skippedRow.gameDate(),
                    homeTeam.get().getId(),
                    awayTeam.get().getId()
            );
            if (existingGame.isEmpty()) {
                log.warn(
                        "Could not match cancellation fallback game. date={}, playText={}, note={}, normalizedAway={}, normalizedHome={}",
                        skippedRow.gameDate(),
                        skippedRow.playText(),
                        skippedRow.note(),
                        matchTeams.awayTeamCode(),
                        matchTeams.homeTeamCode()
                );
                continue;
            }

            Game game = existingGame.get();
            boolean changed = game.syncDetail(
                    status,
                    null,
                    null,
                    null,
                    status == GameStatus.CANCELLED,
                    status == GameStatus.POSTPONED,
                    fallbackCancelReason(status, skippedRow.note()),
                    normalizeRawCancelText(skippedRow.note()),
                    null,
                    null,
                    null,
                    null,
                    appliedAt
            );
            if (changed) {
                gameRepository.save(game);
                updatedCount++;
            }
        }
        return updatedCount;
    }

    private FallbackMatchTeams parseFallbackTeams(String playText) {
        if (playText == null || playText.isBlank()) {
            return null;
        }
        String normalized = SCORE_DIGIT_PATTERN.matcher(playText).replaceAll("")
                .replace(" ", "")
                .trim();
        String[] parts = normalized.split("(?i)vs");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        try {
            TeamDefinition away = TeamCatalog.fromProviderName(parts[0].trim());
            TeamDefinition home = TeamCatalog.fromProviderName(parts[1].trim());
            return new FallbackMatchTeams(away.teamCode(), home.teamCode());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private GameStatus fallbackStatus(String note) {
        String normalized = note == null ? "" : note.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("연기") || normalized.contains("순연") || normalized.contains("postponed")) {
            return GameStatus.POSTPONED;
        }
        if (normalized.contains("우천취소")
                || normalized.contains("경기취소")
                || normalized.contains("취소")
                || normalized.contains("rain")
                || normalized.contains("cancel")) {
            return GameStatus.CANCELLED;
        }
        return GameStatus.SCHEDULED;
    }

    private boolean isCancellationTarget(GameStatus status) {
        return status == GameStatus.CANCELLED || status == GameStatus.POSTPONED;
    }

    private GameCancelReason fallbackCancelReason(GameStatus status, String note) {
        if (status != GameStatus.CANCELLED) {
            return null;
        }
        String normalized = note == null ? "" : note.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("우천") || normalized.contains("rain")) {
            return GameCancelReason.RAIN;
        }
        if (normalized.contains("그라운드")) {
            return GameCancelReason.GROUND;
        }
        if (normalized.isBlank() || "-".equals(normalized) || normalized.contains("취소") || normalized.contains("cancel")) {
            return GameCancelReason.UNKNOWN;
        }
        return GameCancelReason.ETC;
    }

    private String normalizeRawCancelText(String note) {
        if (note == null) {
            return null;
        }
        String normalized = note.trim();
        return normalized.isBlank() || "-".equals(normalized) ? null : normalized;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private TeamUpsertResult upsertTeam(String providerTeamName) {
        TeamDefinition definition = TeamCatalog.fromProviderName(providerTeamName);

        return teamRepository.findByTeamCode(definition.teamCode())
                .map(existingTeam -> {
                    boolean updated = existingTeam.syncMetadata(
                            definition.name(),
                            definition.shortName(),
                            definition.englishName(),
                            definition.logoUrl()
                    );
                    if (updated) {
                        teamRepository.save(existingTeam);
                    }
                    return new TeamUpsertResult(existingTeam, false, updated);
                })
                .orElseGet(() -> new TeamUpsertResult(
                        teamRepository.save(new Team(
                                UUID.randomUUID(),
                                definition.teamCode(),
                                definition.name(),
                                definition.shortName(),
                                definition.englishName(),
                                definition.logoUrl()
                        )),
                        true,
                        false
                ));
    }

    private OffsetDateTime sourceUpdatedAtForApply(ParsedScheduleGame parsedGame, OffsetDateTime appliedAt) {
        return parsedGame.sourceUpdatedAt() == null ? appliedAt : parsedGame.sourceUpdatedAt();
    }

    private String buildPublicGameId(ParsedScheduleGame parsedGame, Team homeTeam, Team awayTeam) {
        return "%s-%s-%s".formatted(
                parsedGame.gameDate().format(PUBLIC_GAME_ID_DATE_FORMAT),
                TeamCatalog.publicCodeForTeamCode(homeTeam.getTeamCode()),
                TeamCatalog.publicCodeForTeamCode(awayTeam.getTeamCode())
        );
    }

    private record TeamUpsertResult(Team team, boolean created, boolean updated) {
    }

    private record PersistResult(
            int teamCreatedCount,
            int teamUpdatedCount,
            int gameCreatedCount,
            int gameUpdatedCount
    ) {
    }

    private record FallbackMatchTeams(
            String awayTeamCode,
            String homeTeamCode
    ) {
    }
}
