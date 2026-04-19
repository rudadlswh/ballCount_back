package com.kbo.crawlerapi.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.kbo.crawlerapi.crawler.KboScheduleClient;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboScheduleParser;
import com.kbo.crawlerapi.parser.KboScheduleParser.MonthlyScheduleParseResult;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;
import com.kbo.crawlerapi.repository.ScheduleGameWriteRepository;
import com.kbo.crawlerapi.repository.TeamRepository;
import com.kbo.crawlerapi.support.TeamCatalog;
import com.kbo.crawlerapi.support.TeamCatalog.TeamDefinition;

@Service
public class KboScheduleImportService {

    private static final Logger log = LoggerFactory.getLogger(KboScheduleImportService.class);

    private final KboScheduleClient kboScheduleClient;
    private final KboScheduleParser kboScheduleParser;
    private final TeamRepository teamRepository;
    private final ScheduleGameWriteRepository scheduleGameWriteRepository;
    private final CrawlJobTrackingService crawlJobTrackingService;
    private final Clock applicationClock;

    public KboScheduleImportService(
            KboScheduleClient kboScheduleClient,
            KboScheduleParser kboScheduleParser,
            TeamRepository teamRepository,
            ScheduleGameWriteRepository scheduleGameWriteRepository,
            CrawlJobTrackingService crawlJobTrackingService,
            Clock applicationClock
    ) {
        this.kboScheduleClient = kboScheduleClient;
        this.kboScheduleParser = kboScheduleParser;
        this.teamRepository = teamRepository;
        this.scheduleGameWriteRepository = scheduleGameWriteRepository;
        this.crawlJobTrackingService = crawlJobTrackingService;
        this.applicationClock = applicationClock;
    }

    public ScheduleIngestionResult importMonthlySchedule(YearMonth yearMonth) {
        String targetKey = yearMonth.toString();
        var crawlJob = crawlJobTrackingService.createRunningScheduleImportJob(targetKey);
        String responseBody;
        MonthlyScheduleParseResult parseResult;

        try {
            responseBody = kboScheduleClient.fetchMonthlySchedule(yearMonth);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "request", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            parseResult = kboScheduleParser.parseMonthlyScheduleResult(responseBody, yearMonth);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        for (var skippedRow : parseResult.skippedRows()) {
            log.warn("Skipped schedule row during import. date={}, reason={}, playText={}, note={}",
                    skippedRow.gameDate(),
                    skippedRow.reason(),
                    skippedRow.playText(),
                    skippedRow.note());
        }

        try {
            PersistResult persistResult = persistParsedGames(parseResult.games());
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
        String responseBody;
        MonthlyScheduleParseResult parseResult;

        log.info("Starting daily KBO schedule crawl. requestedDate={}, derivedMonth={}", date, yearMonth);

        try {
            responseBody = kboScheduleClient.fetchMonthlySchedule(yearMonth);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "request", exception.getMessage(), exception, 0);
            throw exception;
        }

        try {
            parseResult = kboScheduleParser.parseMonthlyScheduleResult(responseBody, yearMonth);
        } catch (Exception exception) {
            crawlJobTrackingService.markFailed(crawlJob.getId(), "parse", exception.getMessage(), exception, 0);
            throw exception;
        }

        List<ParsedScheduleGame> gamesForDate = parseResult.games().stream()
                .filter(parsedGame -> date.equals(parsedGame.gameDate()))
                .toList();
        List<KboScheduleParser.SkippedScheduleRow> skippedRowsForDate = parseResult.skippedRows().stream()
                .filter(skippedRow -> date.equals(skippedRow.gameDate()))
                .toList();

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
            log.warn("Skipped schedule row during daily import. date={}, reason={}, playText={}, note={}",
                    skippedRow.gameDate(),
                    skippedRow.reason(),
                    skippedRow.playText(),
                    skippedRow.note());
        }

        try {
            PersistResult persistResult = persistParsedGames(gamesForDate);
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

    private int skippedMissingProviderGameIdCount(List<KboScheduleParser.SkippedScheduleRow> skippedRows) {
        return (int) skippedRows.stream()
                .filter(skippedRow -> "MISSING_PROVIDER_GAME_ID".equals(skippedRow.reason()))
                .count();
    }

    protected PersistResult persistParsedGames(java.util.List<ParsedScheduleGame> parsedGames) {
        OffsetDateTime appliedAt = OffsetDateTime.now(applicationClock);
        int teamCreatedCount = 0;
        int teamUpdatedCount = 0;
        int gameCreatedCount = 0;
        int gameUpdatedCount = 0;

        for (ParsedScheduleGame parsedGame : parsedGames) {
            TeamUpsertResult awayTeamResult = upsertTeam(parsedGame.awayProviderTeamName());
            TeamUpsertResult homeTeamResult = upsertTeam(parsedGame.homeProviderTeamName());

            teamCreatedCount += awayTeamResult.created() ? 1 : 0;
            teamCreatedCount += homeTeamResult.created() ? 1 : 0;
            teamUpdatedCount += awayTeamResult.updated() ? 1 : 0;
            teamUpdatedCount += homeTeamResult.updated() ? 1 : 0;

            ScheduleGameWriteRepository.GameWriteResult gameResult = scheduleGameWriteRepository.upsertScheduleGame(
                    parsedGame,
                    awayTeamResult.team(),
                    homeTeamResult.team(),
                    sourceUpdatedAtForApply(parsedGame, appliedAt)
            );
            gameCreatedCount += gameResult.created() ? 1 : 0;
            gameUpdatedCount += gameResult.updated() ? 1 : 0;
        }

        return new PersistResult(teamCreatedCount, teamUpdatedCount, gameCreatedCount, gameUpdatedCount);
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

    private record TeamUpsertResult(Team team, boolean created, boolean updated) {
    }

    private record PersistResult(
            int teamCreatedCount,
            int teamUpdatedCount,
            int gameCreatedCount,
            int gameUpdatedCount
    ) {
    }
}
