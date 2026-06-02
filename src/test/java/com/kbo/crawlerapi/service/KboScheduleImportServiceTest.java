package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.crawler.KboScheduleClient;
import com.kbo.crawlerapi.crawler.KboScheduleClient.KboScheduleEndpointException;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboGameListParser;
import com.kbo.crawlerapi.parser.KboScheduleParser;
import com.kbo.crawlerapi.parser.KboScheduleParser.MonthlyScheduleParseResult;
import com.kbo.crawlerapi.parser.KboScheduleParser.ParsedScheduleGame;
import com.kbo.crawlerapi.parser.KboScheduleParser.SkippedScheduleRow;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.ScheduleGameWriteRepository;
import com.kbo.crawlerapi.repository.TeamRepository;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class KboScheduleImportServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private GameRepository gameRepository;

    private StubKboScheduleClient kboScheduleClient;
    private StubKboScheduleParser kboScheduleParser;
    private StubKboGameDetailClient kboGameDetailClient;
    private KboGameListParser kboGameListParser;
    private StubCrawlJobTrackingService crawlJobTrackingService;
    private StubScheduleGameWriteRepository scheduleGameWriteRepository;
    private KboScheduleImportService kboScheduleImportService;
    private OffsetDateTime appliedAt;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-04-11T12:00:00Z"), ZoneId.of("Asia/Seoul"));
        appliedAt = OffsetDateTime.now(fixedClock);
        kboScheduleClient = new StubKboScheduleClient();
        kboScheduleParser = new StubKboScheduleParser();
        kboGameDetailClient = new StubKboGameDetailClient();
        kboGameListParser = new KboGameListParser(new com.fasterxml.jackson.databind.ObjectMapper());
        crawlJobTrackingService = new StubCrawlJobTrackingService();
        scheduleGameWriteRepository = new StubScheduleGameWriteRepository();
        kboScheduleImportService = new KboScheduleImportService(
                kboScheduleClient,
                kboScheduleParser,
                kboGameDetailClient,
                kboGameListParser,
                teamRepository,
                gameRepository,
                scheduleGameWriteRepository,
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
    void importMonthlyScheduleSkipsNonCancelledGameWithoutProviderGameId() {
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
                List.of(new SkippedScheduleRow(parsedGame.gameDate(), "MISSING_PROVIDER_GAME_ID", "KIA0vs0두산", "-"))
        );

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 4));

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(result.skippedRowCount()).isEqualTo(1);
        assertThat(result.skippedMissingProviderGameIdCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isZero();
        verify(gameRepository, never()).save(any());
    }

    @Test
    void rainCancelledMissingProviderRowMatchesExistingGameByDateAndTeams() {
        LocalDate gameDate = LocalDate.of(2026, 5, 20);
        Team nc = new Team(UUID.randomUUID(), "nc", "NC Dinos", "NC", "NC Dinos", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game existingGame = new Game(
                UUID.randomUUID(),
                "20260520-DOO-NC",
                "kbo",
                "20260520NCOB0",
                gameDate,
                OffsetDateTime.of(2026, 5, 20, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                doosan,
                nc,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                appliedAt.minusDays(1)
        );
        ParsedScheduleGame parsedGame = missingProviderScheduleGame(gameDate, GameStatus.CANCELLED, "NC", "두산", "우천취소");
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(parsedGame),
                List.of(new SkippedScheduleRow(gameDate, "MISSING_PROVIDER_GAME_ID", "NCvs두산", "우천취소"))
        );

        when(teamRepository.findByTeamCode("nc")).thenReturn(Optional.of(nc));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(gameDate),
                eq(doosan.getId()),
                eq(nc.getId())
        )).thenReturn(Optional.of(existingGame));

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 5));

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(existingGame.getStatus()).isEqualTo(GameStatus.CANCELLED);
        assertThat(existingGame.isCancelled()).isTrue();
        assertThat(existingGame.getCancelReason()).isEqualTo(GameCancelReason.RAIN);
        assertThat(existingGame.getRawCancelText()).isEqualTo("우천취소");
        assertThat(scheduleGameWriteRepository.callCount).isZero();
        verify(gameRepository).save(existingGame);
    }

    @Test
    void cancelledFallbackDoesNotCreateDuplicateWhenExistingGameIsMissing() {
        LocalDate gameDate = LocalDate.of(2026, 5, 20);
        Team nc = new Team(UUID.randomUUID(), "nc", "NC Dinos", "NC", "NC Dinos", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        ParsedScheduleGame parsedGame = missingProviderScheduleGame(gameDate, GameStatus.CANCELLED, "NC", "두산", "우천취소");
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(parsedGame),
                List.of(new SkippedScheduleRow(gameDate, "MISSING_PROVIDER_GAME_ID", "NCvs두산", "우천취소"))
        );

        when(teamRepository.findByTeamCode("nc")).thenReturn(Optional.of(nc));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(gameDate),
                eq(doosan.getId()),
                eq(nc.getId())
        )).thenReturn(Optional.empty());

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 5));

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(scheduleGameWriteRepository.callCount).isZero();
        verify(gameRepository, never()).save(any());
    }

    @Test
    void scoreDigitsAreStrippedBeforeMissingProviderFallbackTeamMatching() {
        LocalDate gameDate = LocalDate.of(2026, 5, 20);
        Team nc = new Team(UUID.randomUUID(), "nc", "NC Dinos", "NC", "NC Dinos", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game existingGame = new Game(
                UUID.randomUUID(),
                "20260520-DOO-NC",
                "kbo",
                "20260520NCOB0",
                gameDate,
                OffsetDateTime.of(2026, 5, 20, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                doosan,
                nc,
                0,
                0,
                null,
                false,
                false,
                null,
                null,
                appliedAt.minusDays(1)
        );
        ParsedScheduleGame parsedGame = missingProviderScheduleGame(gameDate, GameStatus.CANCELLED, "NC", "두산", "취소");
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(parsedGame),
                List.of(new SkippedScheduleRow(gameDate, "MISSING_PROVIDER_GAME_ID", "NC0vs0두산", "취소"))
        );

        when(teamRepository.findByTeamCode("nc")).thenReturn(Optional.of(nc));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(gameDate),
                eq(doosan.getId()),
                eq(nc.getId())
        )).thenReturn(Optional.of(existingGame));

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 5));

        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(existingGame.getStatus()).isEqualTo(GameStatus.CANCELLED);
        verify(gameRepository).findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                "kbo",
                gameDate,
                doosan.getId(),
                nc.getId()
        );
    }

    @Test
    void importMonthlyScheduleCountsPublicGameUpdate() {
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
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
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(false, true);

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 4));

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.parsedGame).isSameAs(parsedGame);
        assertThat(scheduleGameWriteRepository.awayTeam).isSameAs(kia);
        assertThat(scheduleGameWriteRepository.homeTeam).isSameAs(doosan);
        assertThat(scheduleGameWriteRepository.sourceUpdatedAt).isEqualTo(appliedAt);
    }

    @Test
    void importMonthlySchedulePassesStartingPitcherNamesToGameWriter() {
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team lotte = new Team(UUID.randomUUID(), "lotte", "Lotte Giants", "Lotte", "Lotte Giants", null);
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                "20260602LTHT0",
                LocalDate.of(2026, 6, 2),
                OffsetDateTime.of(2026, 6, 2, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "사직",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "롯데",
                null,
                null,
                null,
                null,
                "원정선발",
                "홈선발",
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(parsedGame), List.of());

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("lotte")).thenReturn(Optional.of(lotte));
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(false, true);

        ScheduleIngestionResult result = kboScheduleImportService.importMonthlySchedule(YearMonth.of(2026, 6));

        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.publicGameId).isEqualTo("20260602-LOT-KIA");
        assertThat(scheduleGameWriteRepository.parsedGame.awayStartingPitcherName()).isEqualTo("원정선발");
        assertThat(scheduleGameWriteRepository.parsedGame.homeStartingPitcherName()).isEqualTo("홈선발");
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
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(true, false);

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

        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.parsedGame.providerGameId()).isEqualTo("20260517HTOB0");
        assertThat(scheduleGameWriteRepository.parsedGame.gameDate()).isEqualTo(requestedDate);
        assertThat(scheduleGameWriteRepository.awayTeam).isSameAs(kia);
        assertThat(scheduleGameWriteRepository.homeTeam).isSameAs(doosan);
        assertThat(scheduleGameWriteRepository.sourceUpdatedAt).isEqualTo(appliedAt);
    }

    @Test
    void crawlDayEnrichesScheduledGameStartingPitchersByProviderGameId() {
        LocalDate requestedDate = LocalDate.of(2026, 6, 2);
        Team lotte = new Team(UUID.randomUUID(), "lotte", "Lotte Giants", "Lotte", "Lotte Giants", null);
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                "20260602LTHT0",
                requestedDate,
                OffsetDateTime.of(2026, 6, 2, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "광주",
                GameStatus.SCHEDULED,
                false,
                false,
                "롯데",
                "KIA",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(parsedGame), List.of());
        kboGameDetailClient.gameListResponseBody = """
                {
                  "game": [
                    {
                      "G_ID": "20260602LTHT0",
                      "AWAY_NM": "롯데",
                      "HOME_NM": "KIA",
                      "T_PIT_P_NM": "나균안",
                      "B_PIT_P_NM": "네일"
                    }
                  ]
                }
                """;

        when(teamRepository.findByTeamCode("lotte")).thenReturn(Optional.of(lotte));
        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(false, true);

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(kboGameDetailClient.fetchedGameListDate).isEqualTo(requestedDate);
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.publicGameId).isEqualTo("20260602-KIA-LOT");
        assertThat(scheduleGameWriteRepository.parsedGame.providerGameId()).isEqualTo("20260602LTHT0");
        assertThat(scheduleGameWriteRepository.parsedGame.awayStartingPitcherName()).isEqualTo("나균안");
        assertThat(scheduleGameWriteRepository.parsedGame.homeStartingPitcherName()).isEqualTo("네일");
    }

    @Test
    void crawlDayBackfillsMissingScheduleProviderGameIdFromGameListTeamMatch() {
        LocalDate requestedDate = LocalDate.of(2026, 6, 2);
        Team lotte = new Team(UUID.randomUUID(), "lotte", "Lotte Giants", "Lotte", "Lotte Giants", null);
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        ParsedScheduleGame parsedGame = new ParsedScheduleGame(
                "kbo",
                null,
                requestedDate,
                OffsetDateTime.of(2026, 6, 2, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "광주",
                GameStatus.SCHEDULED,
                false,
                false,
                "롯데",
                "KIA",
                null,
                null,
                null,
                null,
                null
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(parsedGame),
                List.of(new SkippedScheduleRow(requestedDate, "MISSING_PROVIDER_GAME_ID", "롯데vsKIA", null))
        );
        kboGameDetailClient.gameListResponseBody = """
                {
                  "game": [
                    {
                      "G_ID": "20260602LTHT0",
                      "AWAY_NM": "롯데",
                      "HOME_NM": "KIA",
                      "T_PIT_P_NM": "나균안",
                      "B_PIT_P_NM": "네일"
                    }
                  ]
                }
                """;

        when(teamRepository.findByTeamCode("lotte")).thenReturn(Optional.of(lotte));
        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(false, true);

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.gameProcessedCount()).isEqualTo(1);
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(result.skippedMissingProviderGameIdCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.parsedGame.providerGameId()).isEqualTo("20260602LTHT0");
        assertThat(scheduleGameWriteRepository.parsedGame.awayStartingPitcherName()).isEqualTo("나균안");
        assertThat(scheduleGameWriteRepository.parsedGame.homeStartingPitcherName()).isEqualTo("네일");
    }

    @Test
    void crawlDayIgnoresPreviousDateMissingProviderCancellationWithoutWarn(CapturedOutput output) {
        LocalDate requestedDate = LocalDate.of(2026, 5, 21);
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(),
                List.of(new SkippedScheduleRow(LocalDate.of(2026, 5, 20), "MISSING_PROVIDER_GAME_ID", "NCvs두산", "우천취소"))
        );

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.skippedRowCount()).isZero();
        assertThat(result.skippedMissingProviderGameIdCount()).isZero();
        assertThat(result.skippedRows()).isEmpty();
        assertThat(output.toString()).doesNotContain("Skipped malformed");
        assertThat(output.toString()).doesNotContain("Could not match cancellation fallback game");
        assertThat(output.toString()).doesNotContain("NCvs두산");
    }

    @Test
    void crawlDaySkipsRequestedDateNonCancelledMissingProviderWithoutWarn(CapturedOutput output) {
        LocalDate requestedDate = LocalDate.of(2026, 5, 21);
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(),
                List.of(new SkippedScheduleRow(requestedDate, "MISSING_PROVIDER_GAME_ID", "NC0vs0두산", "-"))
        );

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.skippedRowCount()).isEqualTo(1);
        assertThat(result.skippedMissingProviderGameIdCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isZero();
        assertThat(output.toString()).doesNotContain("Could not match cancellation fallback game");
        assertThat(output.toString()).doesNotContain("Skipped malformed");
    }

    @Test
    void crawlDayRequestedDateCancelledMissingProviderUsesFallbackMatching() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 21);
        Team nc = new Team(UUID.randomUUID(), "nc", "NC Dinos", "NC", "NC Dinos", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game existingGame = new Game(
                UUID.randomUUID(),
                "20260521-DOO-NC",
                "kbo",
                "20260521NCOB0",
                requestedDate,
                OffsetDateTime.of(2026, 5, 21, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.SCHEDULED,
                doosan,
                nc,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                appliedAt.minusDays(1)
        );
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(),
                List.of(new SkippedScheduleRow(requestedDate, "MISSING_PROVIDER_GAME_ID", "NCvs두산", "우천취소"))
        );

        when(teamRepository.findByTeamCode("nc")).thenReturn(Optional.of(nc));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(requestedDate),
                eq(doosan.getId()),
                eq(nc.getId())
        )).thenReturn(Optional.of(existingGame));

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(existingGame.getStatus()).isEqualTo(GameStatus.CANCELLED);
        assertThat(existingGame.getCancelReason()).isEqualTo(GameCancelReason.RAIN);
        verify(gameRepository).save(existingGame);
    }

    @Test
    void crawlDayRequestedDateCancelledMissingProviderFallbackFailureLogsWarn(CapturedOutput output) {
        LocalDate requestedDate = LocalDate.of(2026, 5, 21);
        Team nc = new Team(UUID.randomUUID(), "nc", "NC Dinos", "NC", "NC Dinos", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(
                List.of(),
                List.of(new SkippedScheduleRow(requestedDate, "MISSING_PROVIDER_GAME_ID", "NCvs두산", "우천취소"))
        );

        when(teamRepository.findByTeamCode("nc")).thenReturn(Optional.of(nc));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(gameRepository.findByProviderAndGameDateAndHomeTeam_IdAndAwayTeam_Id(
                eq("kbo"),
                eq(requestedDate),
                eq(doosan.getId()),
                eq(nc.getId())
        )).thenReturn(Optional.empty());

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.skippedRowCount()).isEqualTo(1);
        assertThat(result.skippedMissingProviderGameIdCount()).isEqualTo(1);
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(output.toString()).contains("WARN");
        assertThat(output.toString()).contains("Could not match cancellation fallback game");
    }

    @Test
    void crawlDaySavesAndCountsExistingGameOnlyWhenFieldsChange() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 17);
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
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
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(false, true);

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.parsedGame).isSameAs(parsedGame);
        assertThat(scheduleGameWriteRepository.awayTeam).isSameAs(kia);
        assertThat(scheduleGameWriteRepository.homeTeam).isSameAs(doosan);
        assertThat(scheduleGameWriteRepository.sourceUpdatedAt).isEqualTo(appliedAt);
    }

    @Test
    void crawlDayDoesNotSaveOrCountIdenticalExistingGame() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 17);
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
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
        scheduleGameWriteRepository.nextResult = new ScheduleGameWriteRepository.GameWriteResult(false, false);

        DayScheduleIngestionResult result = kboScheduleImportService.crawlDay(requestedDate);

        assertThat(result.gameCreatedCount()).isZero();
        assertThat(result.gameUpdatedCount()).isZero();
        assertThat(scheduleGameWriteRepository.callCount).isEqualTo(1);
        assertThat(scheduleGameWriteRepository.parsedGame).isSameAs(parsedGame);
        assertThat(scheduleGameWriteRepository.awayTeam).isSameAs(kia);
        assertThat(scheduleGameWriteRepository.homeTeam).isSameAs(doosan);
        assertThat(scheduleGameWriteRepository.sourceUpdatedAt).isEqualTo(appliedAt);
    }

    @Test
    void scheduleClientSendsRequiredBrowserAjaxHeaders() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KboScheduleClient client = new TestableKboScheduleClient(builder, "https://www.koreabaseball.com");

        server.expect(requestTo("https://www.koreabaseball.com/ws/Schedule.asmx/GetScheduleList"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.USER_AGENT, containsString("Mozilla/5.0")))
                .andExpect(header(HttpHeaders.ACCEPT, "application/json, text/javascript, */*; q=0.01"))
                .andExpect(header(HttpHeaders.ACCEPT_LANGUAGE, "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"))
                .andExpect(header(HttpHeaders.REFERER, "https://www.koreabaseball.com/Schedule/Schedule.aspx"))
                .andExpect(header("X-Requested-With", "XMLHttpRequest"))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.valueOf("text/plain; charset=UTF-8")));

        KboScheduleClient.ScheduleResponse response = client.fetchMonthlyScheduleResponse(YearMonth.of(2026, 5));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.requestUri()).isEqualTo("https://www.koreabaseball.com/ws/Schedule.asmx/GetScheduleList");
        assertThat(response.method()).isEqualTo("POST");
        server.verify();
    }

    @Test
    void scheduleClientDetectsKboErrorHtmlClearly() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KboScheduleClient client = new TestableKboScheduleClient(builder, "https://www.koreabaseball.com");

        server.expect(requestTo("https://www.koreabaseball.com/ws/Schedule.asmx/GetScheduleList"))
                .andRespond(withSuccess(kboErrorHtml(), MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.fetchMonthlyScheduleResponse(YearMonth.of(2026, 5)))
                .isInstanceOf(KboScheduleEndpointException.class)
                .hasMessage("KBO schedule endpoint returned error page");
        server.verify();
    }

    @Test
    void crawlDayDoesNotInvokeParserForKnownKboErrorPage() {
        LocalDate requestedDate = LocalDate.of(2026, 5, 21);
        kboScheduleClient.responseBody = kboErrorHtml();
        kboScheduleClient.contentType = "text/html";

        assertThatThrownBy(() -> kboScheduleImportService.crawlDay(requestedDate))
                .isInstanceOf(KboScheduleEndpointException.class)
                .hasMessage("KBO schedule endpoint returned error page");

        assertThat(kboScheduleParser.parsedYearMonth).isNull();
        assertThat(crawlJobTrackingService.failedJobId).isEqualTo(crawlJobTrackingService.createdJob.getId());
    }

    @Test
    void crawlMonthAggregatesDailyResultsAcrossSuccessfulMonth() {
        StubMonthlyCrawlService monthlyCrawlService = new StubMonthlyCrawlService();
        monthlyCrawlService.addResult(successResult(LocalDate.of(2026, 4, 1), 1, 4, 0));
        monthlyCrawlService.addResult(successResult(LocalDate.of(2026, 4, 2), 0, 2, 1));

        MonthScheduleIngestionResult result = monthlyCrawlService.crawlMonth(YearMonth.of(2026, 4));

        assertThat(result.yearMonth()).isEqualTo(YearMonth.of(2026, 4));
        assertThat(result.from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(result.to()).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(result.totalDays()).isEqualTo(30);
        assertThat(result.successDays()).isEqualTo(30);
        assertThat(result.failedDays()).isZero();
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(6);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isZero();
        assertThat(result.failures()).isEmpty();
        assertThat(result.dailyResults()).hasSize(30);
        assertThat(result.dailyResults().get(0).date()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(result.dailyResults().get(0).status()).isEqualTo(MonthScheduleIngestionResult.DailyStatus.SUCCESS);
        assertThat(result.dailyResults().get(1).date()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(result.dailyResults().get(1).updatedCount()).isEqualTo(2);
    }

    @Test
    void crawlMonthIteratesFromFirstDayToLastDay() {
        StubMonthlyCrawlService monthlyCrawlService = new StubMonthlyCrawlService();

        MonthScheduleIngestionResult result = monthlyCrawlService.crawlMonth(YearMonth.of(2026, 4));

        assertThat(result.totalDays()).isEqualTo(30);
        assertThat(monthlyCrawlService.requestedDates).hasSize(30);
        assertThat(monthlyCrawlService.requestedDates.get(0)).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(monthlyCrawlService.requestedDates.get(29)).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void crawlMonthHandlesLeapYearFebruary() {
        StubMonthlyCrawlService monthlyCrawlService = new StubMonthlyCrawlService();

        MonthScheduleIngestionResult result = monthlyCrawlService.crawlMonth(YearMonth.of(2024, 2));

        assertThat(result.from()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(result.to()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(result.totalDays()).isEqualTo(29);
        assertThat(monthlyCrawlService.requestedDates).hasSize(29);
        assertThat(monthlyCrawlService.requestedDates.get(28)).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void crawlMonthContinuesAfterOneDayFails() {
        StubMonthlyCrawlService monthlyCrawlService = new StubMonthlyCrawlService();
        monthlyCrawlService.addResult(successResult(LocalDate.of(2026, 4, 14), 1, 0, 0));
        monthlyCrawlService.addFailure(LocalDate.of(2026, 4, 15), new IllegalStateException("KBO source timeout"));
        monthlyCrawlService.addResult(successResult(LocalDate.of(2026, 4, 16), 0, 3, 1));

        MonthScheduleIngestionResult result = monthlyCrawlService.crawlMonth(YearMonth.of(2026, 4));

        assertThat(result.successDays()).isEqualTo(29);
        assertThat(result.failedDays()).isEqualTo(1);
        assertThat(result.createdCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(3);
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.failures()).containsExactly(
                new MonthScheduleIngestionResult.Failure(LocalDate.of(2026, 4, 15), "KBO source timeout")
        );
        assertThat(result.dailyResults()).extracting(MonthScheduleIngestionResult.DailyResult::status)
                .contains(MonthScheduleIngestionResult.DailyStatus.FAILED);
        assertThat(monthlyCrawlService.requestedDates.get(15)).isEqualTo(LocalDate.of(2026, 4, 16));
        assertThat(monthlyCrawlService.requestedDates.get(29)).isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void crawlMonthDoesNotCreateDuplicateGamesWhenRepeated() {
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team doosan = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Team ssg = new Team(UUID.randomUUID(), "ssg", "SSG Landers", "SSG", "SSG Landers", null);
        Team lg = new Team(UUID.randomUUID(), "lg", "LG Twins", "LG", "LG Twins", null);
        InMemoryUpsertScheduleGameWriteRepository inMemoryRepository = new InMemoryUpsertScheduleGameWriteRepository();

        kboScheduleImportService = new KboScheduleImportService(
                kboScheduleClient,
                kboScheduleParser,
                teamRepository,
                inMemoryRepository,
                crawlJobTrackingService,
                Clock.fixed(Instant.parse("2026-04-11T12:00:00Z"), ZoneId.of("Asia/Seoul"))
        );

        ParsedScheduleGame firstGame = new ParsedScheduleGame(
                "kbo",
                "20260401HTOB0",
                LocalDate.of(2026, 4, 1),
                OffsetDateTime.of(2026, 4, 1, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
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
        ParsedScheduleGame secondGame = new ParsedScheduleGame(
                "kbo",
                "20260402SSLG0",
                LocalDate.of(2026, 4, 2),
                OffsetDateTime.of(2026, 4, 2, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "문학",
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
        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(firstGame, secondGame), List.of());

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("doosan")).thenReturn(Optional.of(doosan));
        when(teamRepository.findByTeamCode("ssg")).thenReturn(Optional.of(ssg));
        when(teamRepository.findByTeamCode("lg")).thenReturn(Optional.of(lg));

        MonthScheduleIngestionResult firstRun = kboScheduleImportService.crawlMonth(YearMonth.of(2026, 4));
        MonthScheduleIngestionResult secondRun = kboScheduleImportService.crawlMonth(YearMonth.of(2026, 4));

        assertThat(firstRun.createdCount()).isEqualTo(2);
        assertThat(secondRun.createdCount()).isZero();
        assertThat(secondRun.updatedCount()).isZero();
        assertThat(secondRun.failedDays()).isZero();
        assertThat(inMemoryRepository.storedGames).hasSize(2);
    }

    @Test
    void crawlMonthDoesNotOverwriteExistingStartingPitchersWithBlankIncomingNames() {
        Team kia = new Team(UUID.randomUUID(), "kia", "KIA Tigers", "KIA", "KIA Tigers", null);
        Team lotte = new Team(UUID.randomUUID(), "lotte", "Lotte Giants", "Lotte", "Lotte Giants", null);
        InMemoryUpsertScheduleGameWriteRepository inMemoryRepository = new InMemoryUpsertScheduleGameWriteRepository();

        kboScheduleImportService = new KboScheduleImportService(
                kboScheduleClient,
                kboScheduleParser,
                teamRepository,
                inMemoryRepository,
                crawlJobTrackingService,
                Clock.fixed(Instant.parse("2026-04-11T12:00:00Z"), ZoneId.of("Asia/Seoul"))
        );

        ParsedScheduleGame withPitchers = new ParsedScheduleGame(
                "kbo",
                "20260602LTHT0",
                LocalDate.of(2026, 6, 2),
                OffsetDateTime.of(2026, 6, 2, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "사직",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "롯데",
                null,
                null,
                null,
                null,
                "원정선발",
                "홈선발",
                null
        );
        ParsedScheduleGame blankPitchers = new ParsedScheduleGame(
                "kbo",
                "20260602LTHT0",
                LocalDate.of(2026, 6, 2),
                OffsetDateTime.of(2026, 6, 2, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "사직",
                GameStatus.SCHEDULED,
                false,
                false,
                "KIA",
                "롯데",
                null,
                null,
                null,
                null,
                " ",
                null,
                null
        );

        when(teamRepository.findByTeamCode("kia")).thenReturn(Optional.of(kia));
        when(teamRepository.findByTeamCode("lotte")).thenReturn(Optional.of(lotte));

        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(withPitchers), List.of());
        kboScheduleImportService.crawlMonth(YearMonth.of(2026, 6));

        kboScheduleParser.parseResult = new MonthlyScheduleParseResult(List.of(blankPitchers), List.of());
        MonthScheduleIngestionResult secondRun = kboScheduleImportService.crawlMonth(YearMonth.of(2026, 6));

        assertThat(secondRun.updatedCount()).isZero();
        var storedGame = inMemoryRepository.storedGames.get("kbo:20260602LTHT0");
        assertThat(storedGame.awayStartingPitcherName()).isEqualTo("원정선발");
        assertThat(storedGame.homeStartingPitcherName()).isEqualTo("홈선발");
    }

    private DayScheduleIngestionResult successResult(LocalDate date, int createdCount, int updatedCount, int skippedCount) {
        return new DayScheduleIngestionResult(
                date,
                0,
                0,
                createdCount + updatedCount,
                createdCount,
                updatedCount,
                skippedCount,
                0,
                0,
                List.of()
        );
    }

    private ParsedScheduleGame missingProviderScheduleGame(
            LocalDate gameDate,
            GameStatus status,
            String awayTeamName,
            String homeTeamName,
            String rawCancelText
    ) {
        return new ParsedScheduleGame(
                "kbo",
                null,
                gameDate,
                OffsetDateTime.of(gameDate.getYear(), gameDate.getMonthValue(), gameDate.getDayOfMonth(), 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                status,
                status == GameStatus.CANCELLED,
                status == GameStatus.POSTPONED,
                awayTeamName,
                homeTeamName,
                null,
                null,
                status == GameStatus.CANCELLED ? GameCancelReason.RAIN : null,
                rawCancelText,
                null
        );
    }

    private String kboErrorHtml() {
        return """
                <!DOCTYPE html>
                <html lang="ko">
                <head><title>에러 | KBO홈페이지 </title></head>
                <body>
                  <div class="errorbox">
                    <strong>이용에 불편을 드려 죄송합니다.</strong>
                  </div>
                </body>
                </html>
                """;
    }

    private static final class TestableKboScheduleClient extends KboScheduleClient {
        private TestableKboScheduleClient(RestClient.Builder restClientBuilder, String baseUrl) {
            super(restClientBuilder, baseUrl);
        }
    }

    private static final class StubKboScheduleClient extends KboScheduleClient {

        private String responseBody = "{\"rows\":[]}";
        private String contentType = "application/json";
        private YearMonth fetchedYearMonth;

        @Override
        public String fetchMonthlySchedule(YearMonth yearMonth) {
            return fetchMonthlyScheduleResponse(yearMonth).body();
        }

        @Override
        public KboScheduleClient.ScheduleResponse fetchMonthlyScheduleResponse(YearMonth yearMonth) {
            this.fetchedYearMonth = yearMonth;
            return new KboScheduleClient.ScheduleResponse(
                    responseBody,
                    200,
                    contentType,
                    "https://www.koreabaseball.com/ws/Schedule.asmx/GetScheduleList",
                    "POST",
                    null
            );
        }
    }

    private static final class StubKboGameDetailClient extends KboGameDetailClient {

        private String gameListResponseBody = "{\"game\":[]}";
        private LocalDate fetchedGameListDate;

        @Override
        public KboGameDetailClient.DetailResponse fetchGameListResponse(LocalDate gameDate) {
            this.fetchedGameListDate = gameDate;
            return new KboGameDetailClient.DetailResponse(
                    gameListResponseBody,
                    200,
                    "application/json",
                    "https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList",
                    "POST"
            );
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

    private static final class StubMonthlyCrawlService extends KboScheduleImportService {

        private final Map<LocalDate, DayScheduleIngestionResult> resultsByDate = new HashMap<>();
        private final Map<LocalDate, RuntimeException> failuresByDate = new HashMap<>();
        private final List<LocalDate> requestedDates = new ArrayList<>();

        private StubMonthlyCrawlService() {
            super(null, null, null, null, null, Clock.systemUTC());
        }

        @Override
        public DayScheduleIngestionResult crawlDay(LocalDate date) {
            requestedDates.add(date);
            RuntimeException failure = failuresByDate.get(date);
            if (failure != null) {
                throw failure;
            }
            return resultsByDate.getOrDefault(date, new DayScheduleIngestionResult(
                    date,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of()
            ));
        }

        private void addResult(DayScheduleIngestionResult result) {
            resultsByDate.put(result.date(), result);
        }

        private void addFailure(LocalDate date, RuntimeException exception) {
            failuresByDate.put(date, exception);
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

    private static final class StubScheduleGameWriteRepository implements ScheduleGameWriteRepository {

        private GameWriteResult nextResult = new GameWriteResult(false, false);
        private int callCount;
        private ParsedScheduleGame parsedGame;
        private Team awayTeam;
        private Team homeTeam;
        private String publicGameId;
        private OffsetDateTime sourceUpdatedAt;

        @Override
        public GameWriteResult upsertScheduleGame(
                ParsedScheduleGame parsedGame,
                Team awayTeam,
                Team homeTeam,
                String publicGameId,
                OffsetDateTime sourceUpdatedAt
        ) {
            this.callCount++;
            this.parsedGame = parsedGame;
            this.awayTeam = awayTeam;
            this.homeTeam = homeTeam;
            this.publicGameId = publicGameId;
            this.sourceUpdatedAt = sourceUpdatedAt;
            return nextResult;
        }
    }

    private static final class InMemoryUpsertScheduleGameWriteRepository implements ScheduleGameWriteRepository {

        private final Map<String, StoredGame> storedGames = new HashMap<>();

        @Override
        public GameWriteResult upsertScheduleGame(
                ParsedScheduleGame parsedGame,
                Team awayTeam,
                Team homeTeam,
                String publicGameId,
                OffsetDateTime sourceUpdatedAt
        ) {
            String key = parsedGame.providerGameId() == null
                    ? publicGameId
                    : parsedGame.provider() + ":" + parsedGame.providerGameId();
            StoredGame updated = new StoredGame(
                    publicGameId,
                    parsedGame.providerGameId(),
                    parsedGame.gameDate(),
                    parsedGame.scheduledAt(),
                    parsedGame.stadium(),
                    parsedGame.status().getApiValue(),
                    awayTeam.getId(),
                    homeTeam.getId(),
                    parsedGame.awayScore(),
                    parsedGame.homeScore(),
                    parsedGame.isCancelled(),
                    parsedGame.isPostponed(),
                    normalizedText(parsedGame.awayStartingPitcherName()),
                    normalizedText(parsedGame.homeStartingPitcherName()),
                    sourceUpdatedAt
            );
            StoredGame existing = storedGames.get(key);

            if (existing == null) {
                storedGames.put(key, updated);
                return new GameWriteResult(true, false);
            }

            if (Objects.equals(existing, updated)) {
                return new GameWriteResult(false, false);
            }

            updated = updated.withPreservedPitchers(existing);
            if (Objects.equals(existing, updated)) {
                return new GameWriteResult(false, false);
            }

            storedGames.put(key, updated);
            return new GameWriteResult(false, true);
        }

        private String normalizedText(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }

        private record StoredGame(
                String publicGameId,
                String providerGameId,
                LocalDate gameDate,
                OffsetDateTime scheduledAt,
                String stadium,
                String status,
                UUID awayTeamId,
                UUID homeTeamId,
                Integer awayScore,
                Integer homeScore,
                boolean cancelled,
                boolean postponed,
                String awayStartingPitcherName,
                String homeStartingPitcherName,
                OffsetDateTime sourceUpdatedAt
        ) {
            private StoredGame withPreservedPitchers(StoredGame existing) {
                return new StoredGame(
                        publicGameId,
                        providerGameId,
                        gameDate,
                        scheduledAt,
                        stadium,
                        status,
                        awayTeamId,
                        homeTeamId,
                        awayScore,
                        homeScore,
                        cancelled,
                        postponed,
                        awayStartingPitcherName == null ? existing.awayStartingPitcherName : awayStartingPitcherName,
                        homeStartingPitcherName == null ? existing.homeStartingPitcherName : homeStartingPitcherName,
                        sourceUpdatedAt
                );
            }
        }
    }
}
