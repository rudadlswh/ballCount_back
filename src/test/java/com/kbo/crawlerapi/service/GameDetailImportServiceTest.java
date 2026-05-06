package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kbo.crawlerapi.crawler.KboGameDetailClient;
import com.kbo.crawlerapi.domain.CrawlJob;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.parser.KboGameDetailParser;
import com.kbo.crawlerapi.parser.KboLineScoreParser;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@ExtendWith(MockitoExtension.class)
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

    private StubCrawlJobTrackingService crawlJobTrackingService;

    private GameDetailImportService gameDetailImportService;

    @BeforeEach
    void setUp() {
        kboGameDetailClient = new StubKboGameDetailClient();
        kboGameDetailParser = new StubKboGameDetailParser();
        kboLineScoreParser = new StubKboLineScoreParser();
        crawlJobTrackingService = new StubCrawlJobTrackingService();
        gameDetailImportService = new GameDetailImportService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                kboGameDetailClient,
                kboGameDetailParser,
                kboLineScoreParser,
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
        assertThat(crawlJobTrackingService.succeededJobId).isEqualTo(crawlJob.getId());
        assertThat(crawlJobTrackingService.snapshotCreated).isFalse();
        assertThat(crawlJobTrackingService.importedLineScoreCount).isEqualTo(2);
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
        assertThat(crawlJobTrackingService.succeededJobId).isEqualTo(crawlJob.getId());
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

    private static final class StubKboGameDetailClient extends KboGameDetailClient {

        private String detailBody;
        private String lineScoreBody;
        private String lastRequestedScoreboardProviderGameId;

        @Override
        public String fetchGameList(LocalDate gameDate) {
            return detailBody;
        }

        @Override
        public String fetchScoreBoard(String providerGameId, int seasonId) {
            lastRequestedScoreboardProviderGameId = providerGameId;
            return lineScoreBody;
        }
    }

    private static final class StubKboGameDetailParser extends KboGameDetailParser {

        private List<KboGameDetailParser.ParsedGameDetail> parsedGames = List.of();

        private StubKboGameDetailParser() {
            super(new ObjectMapper());
        }

        @Override
        public List<KboGameDetailParser.ParsedGameDetail> parseGameList(String responseBody) {
            return parsedGames;
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

    private static final class StubCrawlJobTrackingService extends CrawlJobTrackingService {

        private CrawlJob createdJob;
        private UUID succeededJobId;
        private boolean snapshotCreated;
        private int importedLineScoreCount;

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
    }
}
