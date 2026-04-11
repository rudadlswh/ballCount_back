package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kbo.crawlerapi.crawler.KboScheduleClient;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboScheduleParser;
import com.kbo.crawlerapi.parser.KboScheduleParser.MonthlyScheduleParseResult;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;
import com.kbo.crawlerapi.parser.KboScheduleParser.SkippedScheduleRow;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.TeamRepository;

@ExtendWith(MockitoExtension.class)
class KboScheduleImportServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private GameRepository gameRepository;

    private StubKboScheduleClient kboScheduleClient;
    private StubKboScheduleParser kboScheduleParser;
    private StubCrawlJobTrackingService crawlJobTrackingService;
    private KboScheduleImportService kboScheduleImportService;
    private OffsetDateTime appliedAt;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-11T12:00:00Z"), ZoneId.of("Asia/Seoul"));
        appliedAt = OffsetDateTime.now(fixedClock);
        kboScheduleClient = new StubKboScheduleClient();
        kboScheduleParser = new StubKboScheduleParser();
        crawlJobTrackingService = new StubCrawlJobTrackingService();
        kboScheduleImportService = new KboScheduleImportService(
                kboScheduleClient,
                kboScheduleParser,
                teamRepository,
                gameRepository,
                crawlJobTrackingService,
                fixedClock
        );
        crawlJobTrackingService.createdJob = new CrawlJob(
                UUID.randomUUID(),
                "schedule-import",
                "month",
                "2026-04",
                "running",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    @Test
    void importMonthlySchedulePersistsGameWithoutProviderGameId() {
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                null,
                LocalDate.of(2026, 4, 17),
                OffsetDateTime.of(2026, 4, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "두산",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(parsedGame),
                List.of(new SkippedScheduleRow(parsedGame.gameDate(), "MISSING_PROVIDER_GAME_ID", "KIA vs 두산", null))
        );

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(LocalDate.of(2026, 4, 17)),
                eq(doosan.getId()),
                eq(kia.getId())
        )).thenReturn(Optional.empty());
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 4));

        assertThat(result.gameCreatedCount()).isEqualTo(1);
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(result.skippedRowCount()).isEqualTo(1);
        assertThat(result.skippedMissingProviderGameIdCount()).isEqualTo(1);

        ArgumentCaptor<Game> savedGame = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(savedGame.capture());
        assertThat(savedGame.getValue().getProviderGameId()).isNull();
        assertThat(savedGame.getValue().getGameDate()).isEqualTo(LocalDate.of(2026, 4, 17));
        assertThat(savedGame.getValue().getSourceUpdatedAt()).isEqualTo(appliedAt);
    }

    @Test
    void importMonthlyScheduleBackfillsProviderGameIdViaNaturalKeyMatch() {
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game existingGame = new Game(
                UUID.randomUUID(),
                "20260417-DOO-KIA",
                "kbo",
                null,
                LocalDate.of(2026, 4, 17),
                OffsetDateTime.of(2026, 4, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                doosan,
                kia,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                null
        );
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                "20260417HTOB0",
                LocalDate.of(2026, 4, 17),
                OffsetDateTime.of(2026, 4, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "두산",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(parsedGame), List.of());

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndProviderGameId("kbo", "20260417HTOB0")).thenReturn(Optional.empty());
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(LocalDate.of(2026, 4, 17)),
                eq(doosan.getId()),
                eq(kia.getId())
        )).thenReturn(Optional.of(existingGame));

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 4));

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(existingGame.getProviderGameId()).isEqualTo("20260417HTOB0");
        assertThat(existingGame.getSourceUpdatedAt()).isEqualTo(appliedAt);
        verify(gameRepository).save(existingGame);
    }

    @Test
    void crawlDayFetchesMonthAndPersistsOnlyRequestedDate() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 17);
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        ParsedScheduleGame requestedGame = new ParsedScheduleGame(
                "kbo",
                "20260517HTOB0",
                requestedDate,
                OffsetDateTime.of(2026, 5, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "두산",
                null,
                null,
                null,
                null,
                null
        );
        ParsedScheduleGame otherDateGame = new ParsedScheduleGame(
                "kbo",
                "20260518SSLG0",
                LocalDate.of(2026, 5, 18),
                OffsetDateTime.of(2026, 5, 18, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "SSG",
                "LG",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(requestedGame, otherDateGame),
                List.of(
                        new SkippedScheduleRow(requestedDate, "MISSING_PROVIDER_GAME_ID", "KIA vs 두산", null),
                        new SkippedScheduleRow(LocalDate.of(2026, 5, 18), "MISSING_PROVIDER_GAME_ID", "SSG vs LG", null)
                )
        );

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndProviderGameId("kbo", "20260517HTOB0")).thenReturn(Optional.empty());
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(requestedDate),
                eq(doosan.getId()),
                eq(kia.getId())
        )).thenReturn(Optional.empty());
        when(gameRepository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(kboScheduleClient.fetchedYearMonth).isEqualTo(YearMonth.of(2026, 5));
        assertThat(kboScheduleParser.parsedYearMonth).isEqualTo(YearMonth.of(2026, 5));
        assertThat(crawlJobTrackingService.dailyTargetKey).isEqualTo("2026-05-17");
        assertThat(result.date()).isEqualTo(requestedDate);
        assertThat(result.gameProcessedCount()).isEqualTo(1);
        assertThat(result.gameCreatedCount()).isEqualTo(1);
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(result.skippedRowCount()).isEqualTo(1);
        assertThat(result.skippedMissingProviderGameIdCount()).isEqualTo(1);

        ArgumentCaptor<Game> savedGame = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(savedGame.capture());
        assertThat(savedGame.getValue().getProviderGameId()).isEqualTo("20260517HTOB0");
        assertThat(savedGame.getValue().getGameDate()).isEqualTo(requestedDate);
        assertThat(savedGame.getValue().getSourceUpdatedAt()).isEqualTo(appliedAt);
    }

    @Test
    void crawlDaySavesAndCountsExistingGameOnlyWhenFieldsChange() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 17);
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game existingGame = new Game(
                UUID.randomUUID(),
                "20260517-DOO-KIA",
                "kbo",
                "20260517HTOB0",
                requestedDate,
                OffsetDateTime.of(2026, 5, 17, 17, 0, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                doosan,
                kia,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                OffsetDateTime.of(2026, 5, 17, 9, 0, 0, 0, ZoneOffset.ofHours(9))
        );
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                "20260517HTOB0",
                requestedDate,
                OffsetDateTime.of(2026, 5, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "두산",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(parsedGame), List.of());

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndProviderGameId("kbo", "20260517HTOB0")).thenReturn(Optional.of(existingGame));

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(existingGame.getScheduledAt()).isEqualTo(OffsetDateTime.of(2026, 5, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(existingGame.getSourceUpdatedAt()).isEqualTo(appliedAt);
        verify(gameRepository).save(existingGame);
    }

    @Test
    void crawlDayDoesNotSaveOrCountIdenticalExistingGame() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 17);
        OffsetDateTime existingSourceUpdatedAt = OffsetDateTime.of(2026, 5, 17, 9, 0, 0, 0, ZoneOffset.ofHours(9));
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game existingGame = new Game(
                UUID.randomUUID(),
                "20260517-DOO-KIA",
                "kbo",
                "20260517HTOB0",
                requestedDate,
                OffsetDateTime.of(2026, 5, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                doosan,
                kia,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                existingSourceUpdatedAt
        );
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                "20260517HTOB0",
                requestedDate,
                OffsetDateTime.of(2026, 5, 17, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "두산",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(parsedGame), List.of());

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndProviderGameId("kbo", "20260517HTOB0")).thenReturn(Optional.of(existingGame));

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(existingGame.getSourceUpdatedAt()).isEqualTo(existingSourceUpdatedAt);
        verify(gameRepository, never()).save(any(Game.class));
    }

    private static final class StubKboScheduleClient extends KboScheduleClient {

        private String responseBody = "{\"rows\":[]}";
        private YearMonth fetchedYearMonth;

        @Override
        public String fetchMonthlySchedule(YearMonth yearMonth) {
            this.fetchedYearMonth = yearMonth;
            return responseBody;
        }
    }

    private static final class StubKboScheduleParser extends KboScheduleParser {

        private MonthlyScheduleParseResult parseResult = new MonthlyScheduleParseResult(List.of(), List.of());
        private YearMonth parsedYearMonth;

        private StubKboScheduleParser() {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @Override
        public MonthlyScheduleParseResult parseMonthlyScheduleResult(String responseBody, YearMonth yearMonth) {
            this.parsedYearMonth = yearMonth;
            return parseResult;
        }
    }

    private static final class StubCrawlJobTrackingService extends CrawlJobTrackingService {

        private CrawlJob createdJob;
        private UUID succeededJobId;
        private UUID failedJobId;
        private String dailyTargetKey;

        private StubCrawlJobTrackingService() {
            super(null, null);
        }

        @Override
        public CrawlJob createRunningScheduleImportJob(String targetKey) {
            return createdJob;
        }

        @Override
        public CrawlJob createRunningDailyScheduleImportJob(String targetKey) {
            this.dailyTargetKey = targetKey;
            return createdJob;
        }

        @Override
        public void markScheduleSucceeded(UUID crawlJobId, int skippedRowCount) {
            this.succeededJobId = crawlJobId;
        }

        @Override
        public void markFailed(UUID crawlJobId, String failureStage, String errorMessage, Throwable throwable, int skippedRowCount) {
            this.failedJobId = crawlJobId;
        }
    }
}
