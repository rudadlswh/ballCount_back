package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboBoxscoreParser;
import com.kbo.crawlerapi.parser.KboGameDetailParser;
import com.kbo.crawlerapi.parser.KboLineScoreParser;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class GameDetailImportServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Mock
    private LineScoreRepository lineScoreRepository;

    private StubKboGameDetailClient kboGameDetailClient;

    private StubKboGameDetailParser kboGameDetailParser;

    private StubKboLineScoreParser kboLineScoreParser;

    private StubKboBoxscoreParser kboBoxscoreParser;

    private StubGameBoxscoreRecordService gameBoxscoreRecordService;

    private StubCrawlJobTrackingService crawlJobTrackingService;

    private GameDetailImportService gameDetailImportService;

    @BeforeEach
    void setUp() {
        kboGameDetailClient = new StubKboGameDetailClient();
        kboGameDetailParser = new StubKboGameDetailParser();
        kboLineScoreParser = new StubKboLineScoreParser();
        kboBoxscoreParser = new StubKboBoxscoreParser();
        gameBoxscoreRecordService = new StubGameBoxscoreRecordService();
        crawlJobTrackingService = new StubCrawlJobTrackingService();
        gameDetailImportService = new GameDetailImportService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                kboGameDetailClient,
                kboGameDetailParser,
                kboLineScoreParser,
                kboBoxscoreParser,
                gameBoxscoreRecordService,
                crawlJobTrackingService,
                new BaseRunnerNameResolver()
        );
    }

    @Test
    void doesNotRewriteSnapshotOrLineScoresWhenSourceIsUnchanged() {
        Game game = fixtureGame();
        CrawlJob crawlJob = new CrawlJob(
                UUID.randomUUID(),
                "game-detail-import",
                "game",
                game.getPublicGameId(),
                "running",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.FINAL,
                false,
                false,
                null,
                null,
                2,
                7,
                9,
                "top",
                "Top 9",
                0,
                0,
                3,
                true,
                true,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "detail-hash"
        ));
        kboLineScoreParser.result = new KboLineScoreParser.ParsedLineScoreResult(
                List.of(
                        new KboLineScoreParser.ParsedLineScoreInning(1, 0, 3),
                        new KboLineScoreParser.ParsedLineScoreInning(2, 0, 0)
                ),
                new KboLineScoreParser.ParsedTeamTotals(2, 7, 1, 3),
                new KboLineScoreParser.ParsedTeamTotals(7, 8, 0, 10),
                "line-hash"
        );
        GameSnapshot latestSnapshot = new GameSnapshot(
                UUID.randomUUID(),
                game,
                9,
                "top",
                "Top 9",
                0,
                0,
                3,
                true,
                true,
                false,
                null,
                null,
                7,
                2,
                8,
                7,
                0,
                1,
                10,
                3,
                hash("detail-hash:line-hash"),
                null,
                OffsetDateTime.now()
        );

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.of(latestSnapshot));
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of(
                new LineScore(UUID.randomUUID(), game, 1, 0, 3),
                new LineScore(UUID.randomUUID(), game, 2, 0, 0)
        ));

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(result.snapshotCreated()).isFalse();
        assertThat(result.lineScoresUpdated()).isFalse();
        assertThat(result.lineScoreCount()).isEqualTo(2);
        verify(gameSnapshotRepository, never()).save(any(GameSnapshot.class));
        verify(lineScoreRepository, never()).deleteByGame_Id(any(UUID.class));
        verify(lineScoreRepository, never()).saveAll(any());
        assertThat(crawlJobTrackingService.partialSuccessJobId).isEqualTo(crawlJob.getId());
        assertThat(crawlJobTrackingService.snapshotCreated).isFalse();
        assertThat(crawlJobTrackingService.importedLineScoreCount).isEqualTo(2);
    }

    @Test
    void updatesExistingLineScoreRowsInsteadOfInsertingDuplicatesOnRepeatedImport() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        LineScore existingLineScore = new LineScore(UUID.randomUUID(), game, 1, 0, 3);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.FINAL,
                false,
                false,
                null,
                null,
                4,
                6,
                9,
                "bottom",
                "Bottom 9",
                0,
                0,
                3,
                true,
                true,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "detail-hash-updated"
        ));
        kboLineScoreParser.result = new KboLineScoreParser.ParsedLineScoreResult(
                List.of(new KboLineScoreParser.ParsedLineScoreInning(1, 4, 6)),
                new KboLineScoreParser.ParsedTeamTotals(4, 8, 0, 2),
                new KboLineScoreParser.ParsedTeamTotals(6, 9, 1, 4),
                "line-hash-updated"
        );

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId())))
                .thenReturn(List.of(existingLineScore), List.of(existingLineScore));

        GameDetailImportResult first = gameDetailImportService.importGameDetail(game.getPublicGameId());
        GameDetailImportResult second = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(first.lineScoresUpdated()).isTrue();
        assertThat(second.lineScoresUpdated()).isFalse();
        assertThat(existingLineScore.getAwayRuns()).isEqualTo(4);
        assertThat(existingLineScore.getHomeRuns()).isEqualTo(6);
        ArgumentCaptor<Iterable<LineScore>> lineScoresCaptor = lineScoreIterableCaptor();
        verify(lineScoreRepository, times(1)).saveAll(lineScoresCaptor.capture());
        List<LineScore> savedLineScores = new ArrayList<>();
        lineScoresCaptor.getValue().forEach(savedLineScores::add);
        assertThat(savedLineScores).containsExactly(existingLineScore);
        verify(lineScoreRepository, never()).deleteByGame_Id(any(UUID.class));
    }

    @Test
    void resolvesOfficialDetailByMatchupAndBackfillsProviderGameIdWhenStoredIdIsSynthetic() {
        Game game = syntheticFixtureGame();
        CrawlJob crawlJob = new CrawlJob(
                UUID.randomUUID(),
                "game-detail-import",
                "game",
                game.getPublicGameId(),
                "running",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        String officialProviderGameId = "20260423HHLG0";
        kboGameDetailClient.detailBody = """
                {
                  "game": [
                    {
                      "G_ID": "20260423HHLG0",
                      "HOME_ID": "LG",
                      "AWAY_ID": "HH",
                      "S_NM": "잠실",
                      "G_TM": "18:30"
                    }
                  ]
                }
                """;
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                officialProviderGameId,
                GameStatus.FINAL,
                false,
                false,
                null,
                null,
                6,
                3,
                9,
                "top",
                "Top 9",
                0,
                1,
                3,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(result.providerGameId()).isEqualTo(officialProviderGameId);
        assertThat(game.getProviderGameId()).isEqualTo(officialProviderGameId);
        assertThat(game.getStatus()).isEqualTo(GameStatus.FINAL);
        assertThat(game.getAwayScore()).isEqualTo(6);
        assertThat(game.getHomeScore()).isEqualTo(3);
        assertThat(kboGameDetailClient.lastRequestedScoreboardProviderGameId).isEqualTo(officialProviderGameId);
        assertThat(kboGameDetailClient.lastRequestedScoreboardProviderGameId).doesNotStartWith("sched-");
        assertThat(crawlJobTrackingService.partialSuccessJobId).isEqualTo(crawlJob.getId());
    }

    @Test
    void syntheticProviderGameIdIsNeverSentToOfficialDetailEndpoints() {
        Game game = syntheticFixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        String officialProviderGameId = "20260423HHLG0";
        kboGameDetailClient.detailBody = """
                {
                  "game": [
                    {
                      "G_ID": "20260423HHLG0",
                      "HOME_ID": "LG",
                      "AWAY_ID": "HH",
                      "S_NM": "잠실",
                      "G_TM": "18:30",
                      "GAME_STATE_SC": "2",
                      "GAME_INN_NO": 1,
                      "GAME_TB_SC": "T",
                      "SCORE_CK": "1",
                      "T_SCORE_CN": "0",
                      "B_SCORE_CN": "0"
                    }
                  ]
                }
                """;
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                officialProviderGameId,
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                0,
                0,
                1,
                "top",
                "Top 1",
                0,
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(kboGameDetailClient.lastRequestedScoreboardProviderGameId).isEqualTo(officialProviderGameId);
        assertThat(kboGameDetailClient.lastRequestedScoreboardProviderGameId).doesNotStartWith("sched-");
        assertThat(kboGameDetailClient.lastRequestedBoxScoreProviderGameId).isNull();
    }

    @Test
    void scoreBoardRainInterruptionPersistsSuspendedWithoutCancellationFlags() {
        Game game = fixtureGame();
        game.syncDetail(
                GameStatus.LIVE,
                1,
                2,
                "Top 8",
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                OffsetDateTime.now()
        );
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "<div class=\"status\">우천중단</div>";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                1,
                8,
                "top",
                "Top 8",
                0,
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "detail-hash"
        ));
        kboLineScoreParser.result = new KboLineScoreParser.ParsedLineScoreResult(
                List.of(new KboLineScoreParser.ParsedLineScoreInning(8, 2, 1)),
                new KboLineScoreParser.ParsedTeamTotals(2, 5, 0, 1),
                new KboLineScoreParser.ParsedTeamTotals(1, 4, 0, 2),
                "line-hash"
        );

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(game.getStatus()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(game.getStatusReason()).isEqualTo("우천중단");
        assertThat(game.isCancelled()).isFalse();
        assertThat(game.isPostponed()).isFalse();
    }

    @Test
    void weakFinalDetailCanBeOverriddenByScoreBoardRainInterruption() {
        Game game = fixtureGame();
        game.confirmFinal(OffsetDateTime.now());
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "<div class=\"status\">우천중단</div>";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.FINAL,
                false,
                false,
                null,
                null,
                2,
                1,
                8,
                "top",
                "Top 8",
                0,
                0,
                0,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "weak-final-detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(game.getStatus()).isEqualTo(GameStatus.SUSPENDED);
        assertThat(game.getStatusReason()).isEqualTo("우천중단");
        assertThat(game.getFinalConfirmedAt()).isNull();
        assertThat(game.isCancelled()).isFalse();
        assertThat(game.isPostponed()).isFalse();
        assertThat(game.getAwayScore()).isEqualTo(2);
        assertThat(game.getHomeScore()).isEqualTo(1);
        assertThat(game.getInningState()).isEqualTo("Top 8");
    }

    @Test
    void detailParseFailureLogsResponseDiagnostics(CapturedOutput output) {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[{\"G_ID\":\"20260401HTLG0\"}]}";
        kboGameDetailClient.detailContentType = "application/json";
        kboGameDetailParser.parseFailure = new IllegalStateException("Failed to parse KBO game detail response");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> gameDetailImportService.importGameDetail(game.getPublicGameId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Failed to parse KBO game detail response");

        assertThat(output.toString()).contains("detail parse failure");
        assertThat(output.toString()).contains("publicGameId=20260401-LG-KIA");
        assertThat(output.toString()).contains("providerGameId=20260401HTLG0");
        assertThat(output.toString()).contains("requestUrl=https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList");
        assertThat(output.toString()).contains("contentType=application/json");
        assertThat(output.toString()).contains("responseType=json");
        assertThat(output.toString()).contains("bodyPreview=");
    }

    @Test
    void persistsCurrentPitcherAndBatterIntoSnapshot() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                7,
                6,
                "top",
                "Top 6",
                1,
                2,
                1,
                true,
                false,
                false,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                false,
                null,
                null,
                "detail-hash-with-current"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().getCurrentPitcherName()).isEqualTo("홈투수");
        assertThat(snapshotCaptor.getValue().getCurrentBatterName()).isEqualTo("원정타자");
    }

    @Test
    void persistsBaseRunnerNamesIntoSnapshot() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                7,
                6,
                "top",
                "Top 6",
                1,
                2,
                1,
                true,
                true,
                false,
                "1루주자",
                "2루주자",
                null,
                "runner-1",
                "runner-2",
                null,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                false,
                null,
                null,
                "detail-hash-with-runners"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().getFirstBaseRunnerName()).isEqualTo("1루주자");
        assertThat(snapshotCaptor.getValue().getSecondBaseRunnerName()).isEqualTo("2루주자");
        assertThat(snapshotCaptor.getValue().getThirdBaseRunnerName()).isNull();
        assertThat(snapshotCaptor.getValue().getFirstBaseRunnerId()).isEqualTo("runner-1");
        assertThat(snapshotCaptor.getValue().getSecondBaseRunnerId()).isEqualTo("runner-2");
    }

    @Test
    void mapsOfficialBaseRunnerBattingOrdersThroughLineupBeforePersistingSnapshot() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailClient.boxScoreBody = "{\"arrHitter\":[]}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                7,
                6,
                "top",
                "Top 6",
                1,
                2,
                1,
                true,
                false,
                true,
                2,
                0,
                4,
                null,
                null,
                null,
                null,
                null,
                null,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                true,
                null,
                null,
                "detail-hash-with-runner-orders"
        ));
        kboGameDetailParser.lineupData = new KboGameDetailParser.ParsedLineupData(
                List.of(
                        new KboGameDetailParser.ParsedLineupPlayer("2", "SS", "원정2번"),
                        new KboGameDetailParser.ParsedLineupPlayer("4", "1B", "원정4번")
                ),
                List.of(new KboGameDetailParser.ParsedLineupPlayer("2", "SS", "홈2번")),
                "lineup-hash"
        );
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().getFirstBaseRunnerName()).isEqualTo("원정2번");
        assertThat(snapshotCaptor.getValue().getSecondBaseRunnerName()).isNull();
        assertThat(snapshotCaptor.getValue().getThirdBaseRunnerName()).isEqualTo("원정4번");
    }

    @Test
    void importsAndSavesBoxscoreRecordsWhenFinalBoxscoreDataIsAvailable() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailClient.boxScoreBody = "{\"code\":\"100\",\"arrHitter\":[],\"arrPitcher\":[]}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.FINAL,
                false,
                false,
                null,
                null,
                2,
                7,
                9,
                "top",
                "Top 9",
                0,
                0,
                3,
                false,
                false,
                false,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                true,
                null,
                null,
                "final-detail-hash"
        ));
        kboGameDetailParser.lineupData = new KboGameDetailParser.ParsedLineupData(List.of(), List.of(), "lineup-hash");
        kboLineScoreParser.result = new KboLineScoreParser.ParsedLineScoreResult(
                List.of(new KboLineScoreParser.ParsedLineScoreInning(1, 0, 3)),
                new KboLineScoreParser.ParsedTeamTotals(2, 7, 1, 3),
                new KboLineScoreParser.ParsedTeamTotals(7, 8, 0, 10),
                "line-hash"
        );
        kboBoxscoreParser.result = parsedBoxscore(1, 1, 1, 1);

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(result.snapshotCreated()).isTrue();
        assertThat(result.lineScoresUpdated()).isTrue();
        assertThat(kboGameDetailClient.requestedBoxScoreCount).isEqualTo(1);
        assertThat(kboBoxscoreParser.parsedResponseBody).isEqualTo(kboGameDetailClient.boxScoreBody);
        assertThat(gameBoxscoreRecordService.savedGame).isSameAs(game);
        assertThat(gameBoxscoreRecordService.savedBoxscore).isSameAs(kboBoxscoreParser.result);
        assertThat(crawlJobTrackingService.succeededJobId).isEqualTo(crawlJob.getId());
        assertThat(crawlJobTrackingService.partialSuccessJobId).isNull();
        verify(lineScoreRepository).saveAll(any());
    }

    @Test
    void skipsBoxscoreSaveSafelyWhenOfficialBoxscoreHasOnlyCodeAndMessage() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailClient.boxScoreBody = "{\"code\":\"200\",\"msg\":\"입력 문자열의 형식이 잘못되었습니다.\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.FINAL,
                false,
                false,
                null,
                null,
                2,
                7,
                9,
                "top",
                "Top 9",
                0,
                0,
                3,
                false,
                false,
                false,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                true,
                null,
                null,
                "final-detail-hash-empty-boxscore"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");
        kboBoxscoreParser.result = KboBoxscoreParser.ParsedBoxscore.empty();

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(result.status()).isEqualTo("final");
        assertThat(kboBoxscoreParser.parsedResponseBody).isEqualTo(kboGameDetailClient.boxScoreBody);
        assertThat(gameBoxscoreRecordService.savedGame).isNull();
        assertThat(crawlJobTrackingService.succeededJobId).isNull();
        assertThat(crawlJobTrackingService.partialSuccessJobId).isEqualTo(crawlJob.getId());
        assertThat(crawlJobTrackingService.partialSuccessMessage).contains("Final game boxscore records unavailable");
    }

    @Test
    void skipsBoxscoreSaveForLiveImportsEvenWhenLineupBoxscoreWasFetched() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailClient.boxScoreBody = "{\"code\":\"100\",\"arrHitter\":[],\"arrPitcher\":[]}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                7,
                6,
                "top",
                "Top 6",
                1,
                2,
                1,
                true,
                false,
                false,
                2,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                true,
                null,
                null,
                "live-detail-hash-boxscore-fetched"
        ));
        kboGameDetailParser.lineupData = new KboGameDetailParser.ParsedLineupData(
                List.of(new KboGameDetailParser.ParsedLineupPlayer("2", "SS", "원정2번")),
                List.of(),
                "lineup-hash"
        );
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");
        kboBoxscoreParser.result = parsedBoxscore(1, 0, 0, 0);

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(result.status()).isEqualTo("live");
        assertThat(kboGameDetailClient.requestedBoxScoreCount).isEqualTo(1);
        assertThat(kboBoxscoreParser.parsedResponseBody).isNull();
        assertThat(gameBoxscoreRecordService.savedGame).isNull();
    }

    @Test
    void infersFirstBaseRunnerNameFromPreviousBatterWhenOfficialRunnerNameMissing() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                7,
                6,
                "top",
                "Top 6",
                1,
                2,
                0,
                true,
                false,
                false,
                "홈투수",
                "손성빈",
                "홈선발",
                "원정선발",
                false,
                null,
                null,
                "detail-hash-inferred-runner"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");
        GameSnapshot latestSnapshot = new GameSnapshot(
                UUID.randomUUID(),
                game,
                6,
                "top",
                "Top 6",
                1,
                2,
                0,
                false,
                false,
                false,
                "홈투수",
                "전민재",
                7,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                "previous-hash",
                null,
                OffsetDateTime.now()
        );

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.of(latestSnapshot));
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().isRunnerOnFirst()).isTrue();
        assertThat(snapshotCaptor.getValue().getFirstBaseRunnerName()).isEqualTo("전민재");
    }

    @Test
    void preGamePayloadWithNoLiveStateDoesNotWriteSnapshot() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.SCHEDULED,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "pregame-detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        assertThat(result.snapshotCreated()).isFalse();
        verify(gameSnapshotRepository, never()).findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(any(UUID.class));
        verify(gameSnapshotRepository, never()).save(any(GameSnapshot.class));
        assertThat(crawlJobTrackingService.snapshotCreated).isFalse();
    }

    @Test
    void livePayloadWithInningAndCountWritesSnapshotWhenCurrentPlayersAreMissing() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                0,
                0,
                1,
                "bottom",
                "Bottom 1",
                3,
                2,
                1,
                false,
                false,
                false,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                "live-count-detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().getInning()).isEqualTo(1);
        assertThat(snapshotCaptor.getValue().getBalls()).isEqualTo(3);
        assertThat(snapshotCaptor.getValue().getStrikes()).isEqualTo(2);
        assertThat(snapshotCaptor.getValue().getOuts()).isEqualTo(1);
        assertThat(snapshotCaptor.getValue().getCurrentPitcherName()).isNull();
        assertThat(snapshotCaptor.getValue().getCurrentBatterName()).isNull();
    }

    @Test
    void payloadWithCurrentPitcherAndBatterWritesSnapshotEvenWithoutCountState() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.SCHEDULED,
                false,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                false,
                false,
                "홈투수",
                "원정타자",
                null,
                null,
                false,
                null,
                null,
                "current-players-detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.empty());
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().getCurrentPitcherName()).isEqualTo("홈투수");
        assertThat(snapshotCaptor.getValue().getCurrentBatterName()).isEqualTo("원정타자");
    }

    @Test
    void createsCorrectedSnapshotWhenRawHashIsUnchangedButCurrentPlayersWereMissing() {
        Game game = fixtureGame();
        CrawlJob crawlJob = crawlJob(game);
        kboGameDetailClient.detailBody = "{\"game\":[]}";
        kboGameDetailClient.lineScoreBody = "{\"code\":\"100\"}";
        kboGameDetailParser.parsedGames = List.of(new KboGameDetailParser.ParsedGameDetail(
                game.getProviderGameId(),
                GameStatus.LIVE,
                false,
                false,
                null,
                null,
                2,
                7,
                6,
                "top",
                "Top 6",
                1,
                2,
                1,
                true,
                false,
                false,
                "홈투수",
                "원정타자",
                "홈선발",
                "원정선발",
                false,
                null,
                null,
                "detail-hash"
        ));
        kboLineScoreParser.result = KboLineScoreParser.ParsedLineScoreResult.empty("line-hash");
        GameSnapshot latestSnapshot = new GameSnapshot(
                UUID.randomUUID(),
                game,
                6,
                "top",
                "Top 6",
                1,
                2,
                1,
                true,
                false,
                false,
                null,
                null,
                7,
                2,
                null,
                null,
                null,
                null,
                null,
                null,
                hash("detail-hash:line-hash"),
                null,
                OffsetDateTime.now()
        );

        crawlJobTrackingService.createdJob = crawlJob;
        when(gameRepository.findByPublicGameId(eq(game.getPublicGameId()))).thenReturn(Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(Optional.of(latestSnapshot));
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of());

        GameDetailImportResult result = gameDetailImportService.importGameDetail(game.getPublicGameId());

        ArgumentCaptor<GameSnapshot> snapshotCaptor = ArgumentCaptor.forClass(GameSnapshot.class);
        verify(gameSnapshotRepository).save(snapshotCaptor.capture());
        assertThat(result.snapshotCreated()).isTrue();
        assertThat(snapshotCaptor.getValue().getRawHash()).isEqualTo(latestSnapshot.getRawHash());
        assertThat(snapshotCaptor.getValue().getCurrentPitcherName()).isEqualTo("홈투수");
        assertThat(snapshotCaptor.getValue().getCurrentBatterName()).isEqualTo("원정타자");
    }

    private Game fixtureGame() {
        Team homeTeam = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                null
        );
        Team awayTeam = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "kia",
                "KIA Tigers",
                "KIA",
                "KIA Tigers",
                null
        );
        return new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260401-LG-KIA",
                "kbo",
                "20260401HTLG0",
                LocalDate.of(2026, 4, 1),
                OffsetDateTime.of(2026, 4, 1, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.FINAL,
                homeTeam,
                awayTeam,
                7,
                2,
                null,
                false,
                false,
                null,
                null,
                null
        );
    }

    private CrawlJob crawlJob(Game game) {
        return new CrawlJob(
                UUID.randomUUID(),
                "game-detail-import",
                "game",
                game.getPublicGameId(),
                "running",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Iterable<LineScore>> lineScoreIterableCaptor() {
        return ArgumentCaptor.forClass((Class) Iterable.class);
    }

    private Game syntheticFixtureGame() {
        Team homeTeam = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                null
        );
        Team awayTeam = new Team(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "hanwha",
                "Hanwha Eagles",
                "Hanwha",
                "Hanwha Eagles",
                null
        );
        return new Game(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                "20260423-LG-HAN",
                "kbo",
                "sched-202604230930-c5d5aa85-799034c4",
                LocalDate.of(2026, 4, 23),
                OffsetDateTime.of(2026, 4, 23, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.LIVE,
                homeTeam,
                awayTeam,
                0,
                0,
                null,
                false,
                false,
                null,
                null,
                null
        );
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
            throw new IllegalStateException(exception);
        }
    }

    private KboBoxscoreParser.ParsedBoxscore parsedBoxscore(
            int awayBatterCount,
            int homeBatterCount,
            int awayPitcherCount,
            int homePitcherCount
    ) {
        return new KboBoxscoreParser.ParsedBoxscore(
                batterRecords("away", 0, awayBatterCount),
                batterRecords("home", 1, homeBatterCount),
                pitcherRecords("away", 0, awayPitcherCount),
                pitcherRecords("home", 1, homePitcherCount)
        );
    }

    private List<KboBoxscoreParser.ParsedBatterRecord> batterRecords(String teamSide, int sourceGroupIndex, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new KboBoxscoreParser.ParsedBatterRecord(
                        teamSide,
                        sourceGroupIndex,
                        index + 1,
                        "유",
                        "타자" + index,
                        3,
                        1,
                        1,
                        1,
                        null,
                        null,
                        null,
                        null,
                        new BigDecimal("0.333"),
                        index
                ))
                .toList();
    }

    private List<KboBoxscoreParser.ParsedPitcherRecord> pitcherRecords(String teamSide, int sourceGroupIndex, int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> new KboBoxscoreParser.ParsedPitcherRecord(
                        teamSide,
                        sourceGroupIndex,
                        index + 1,
                        "투수" + index,
                        "선발",
                        null,
                        1,
                        0,
                        0,
                        "1",
                        3,
                        12,
                        3,
                        1,
                        0,
                        1,
                        1,
                        0,
                        0,
                        new BigDecimal("1.23"),
                        index
                ))
                .toList();
    }

    private static final class StubKboGameDetailClient extends KboGameDetailClient {

        private String detailBody;
        private String detailContentType = "application/json";
        private String lineScoreBody;
        private String scoreBoardPageBody = "";
        private String boxScoreBody;
        private String lastRequestedScoreboardProviderGameId;
        private String lastRequestedBoxScoreProviderGameId;
        private int requestedBoxScoreCount;

        @Override
        public String fetchGameList(LocalDate gameDate) {
            return detailBody;
        }

        @Override
        public KboGameDetailClient.DetailResponse fetchGameListResponse(LocalDate gameDate) {
            return new KboGameDetailClient.DetailResponse(
                    detailBody,
                    200,
                    detailContentType,
                    "https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList",
                    "POST"
            );
        }

        @Override
        public String fetchScoreBoard(String providerGameId, int seasonId) {
            lastRequestedScoreboardProviderGameId = providerGameId;
            return lineScoreBody;
        }

        @Override
        public String fetchScoreBoardPage(String providerGameId, LocalDate gameDate) {
            return scoreBoardPageBody;
        }

        @Override
        public String fetchBoxScore(String providerGameId, int seasonId) {
            lastRequestedBoxScoreProviderGameId = providerGameId;
            requestedBoxScoreCount++;
            return boxScoreBody;
        }
    }

    private static final class StubKboGameDetailParser extends KboGameDetailParser {

        private List<KboGameDetailParser.ParsedGameDetail> parsedGames = List.of();
        private KboGameDetailParser.ParsedLineupData lineupData = KboGameDetailParser.ParsedLineupData.empty(null);
        private RuntimeException parseFailure;

        private StubKboGameDetailParser() {
            super(new ObjectMapper());
        }

        @Override
        public List<KboGameDetailParser.ParsedGameDetail> parseGameList(String responseBody) {
            if (parseFailure != null) {
                throw parseFailure;
            }
            return parsedGames;
        }

        @Override
        public KboGameDetailParser.ParsedLineupData parseLineupData(String responseBody) {
            return lineupData;
        }
    }

    private static final class StubKboLineScoreParser extends KboLineScoreParser {

        private KboLineScoreParser.ParsedLineScoreResult result = KboLineScoreParser.ParsedLineScoreResult.empty("empty");

        private StubKboLineScoreParser() {
            super(new ObjectMapper());
        }

        @Override
        public KboLineScoreParser.ParsedLineScoreResult parse(String responseBody) {
            return result;
        }
    }

    private static final class StubKboBoxscoreParser extends KboBoxscoreParser {

        private KboBoxscoreParser.ParsedBoxscore result = KboBoxscoreParser.ParsedBoxscore.empty();
        private String parsedResponseBody;

        private StubKboBoxscoreParser() {
            super(new ObjectMapper());
        }

        @Override
        public KboBoxscoreParser.ParsedBoxscore parse(String responseBody) {
            parsedResponseBody = responseBody;
            return result;
        }
    }

    private static final class StubGameBoxscoreRecordService extends GameBoxscoreRecordService {

        private Game savedGame;
        private KboBoxscoreParser.ParsedBoxscore savedBoxscore;

        private StubGameBoxscoreRecordService() {
            super(null);
        }

        @Override
        public GameBoxscoreRecordSaveResult saveBoxscoreRecords(Game game, KboBoxscoreParser.ParsedBoxscore parsedBoxscore) {
            savedGame = game;
            savedBoxscore = parsedBoxscore;
            int batterCount = parsedBoxscore.awayBatters().size() + parsedBoxscore.homeBatters().size();
            int pitcherCount = parsedBoxscore.awayPitchers().size() + parsedBoxscore.homePitchers().size();
            return new GameBoxscoreRecordSaveResult(batterCount, pitcherCount, batterCount, pitcherCount, true);
        }
    }

    private static final class StubCrawlJobTrackingService extends CrawlJobTrackingService {

        private CrawlJob createdJob;
        private UUID succeededJobId;
        private UUID partialSuccessJobId;
        private boolean snapshotCreated;
        private int importedLineScoreCount;
        private String partialSuccessMessage;

        private StubCrawlJobTrackingService() {
            super(null, null);
        }

        @Override
        public CrawlJob createRunningGameDetailImportJob(String targetKey) {
            return createdJob;
        }

        @Override
        public void markGameDetailSucceeded(UUID crawlJobId, boolean snapshotCreated, int importedLineScoreCount) {
            this.succeededJobId = crawlJobId;
            this.snapshotCreated = snapshotCreated;
            this.importedLineScoreCount = importedLineScoreCount;
        }

        @Override
        public void markGameDetailPartialSuccess(
                UUID crawlJobId,
                boolean snapshotCreated,
                int importedLineScoreCount,
                String failureStage,
                String message
        ) {
            this.partialSuccessJobId = crawlJobId;
            this.snapshotCreated = snapshotCreated;
            this.importedLineScoreCount = importedLineScoreCount;
            this.partialSuccessMessage = message;
        }

        @Override
        public void markFailed(UUID crawlJobId, String failureStage, String errorMessage, Throwable throwable, int skippedRowCount) {
        }
    }
}
