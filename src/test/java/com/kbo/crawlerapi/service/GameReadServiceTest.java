package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kbo.crawlerapi.api.dto.GameBoxscoreResponse;
import com.kbo.crawlerapi.api.dto.GameDetailResponse;
import com.kbo.crawlerapi.api.dto.GameLineScoreResponse;
import com.kbo.crawlerapi.api.dto.GamesByDateResponse;
import com.kbo.crawlerapi.api.dto.GamesByMonthResponse;
import com.kbo.crawlerapi.api.dto.ScoreboardResponse;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameCancelReason;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository.BatterRecordReadRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository.PitcherRecordReadRow;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@ExtendWith(MockitoExtension.class)
class GameReadServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-09T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSnapshotRepository gameSnapshotRepository;

    @Mock
    private LineScoreRepository lineScoreRepository;

    @Mock
    private GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository;

    private GameReadService gameReadService;

    @BeforeEach
    void setUp() {
        gameReadService = new GameReadService(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                gameBoxscoreRecordReadRepository,
                FIXED_CLOCK
        );
    }

    @Test
    void getGamesByDateMapsScheduledGameWithNullableScores() {
        Team lg = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                "https://example.com/logos/lg.png"
        );
        Team ssg = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "ssg",
                "SSG Landers",
                "SSG",
                "SSG Landers",
                "https://example.com/logos/ssg.png"
        );
        Game game = new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260409-LG-SSG",
                "kbo",
                "20260409LGSS0",
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "Jamsil",
                GameStatus.SCHEDULED,
                lg,
                ssg,
                null,
                null,
                null,
                false,
                false,
                null,
                null,
                OffsetDateTime.of(2026, 4, 9, 17, 58, 14, 0, ZoneOffset.ofHours(9))
        );
        setTimestamps(game,
                OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 9))))
                .thenReturn(List.of(game));

        GamesByDateResponse response = gameReadService.getGamesByDate(LocalDate.of(2026, 4, 9));

        assertThat(response.date()).isEqualTo(LocalDate.of(2026, 4, 9));
        assertThat(response.games()).hasSize(1);
        assertThat(response.games().get(0).id()).isEqualTo("20260409-LG-SSG");
        assertThat(response.games().get(0).awayScore()).isNull();
        assertThat(response.games().get(0).homeScore()).isNull();
        assertThat(response.games().get(0).status()).isEqualTo("scheduled");
        assertThat(response.games().get(0).cancelReason()).isNull();
        assertThat(response.updatedAt()).isEqualTo(OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(response.isStale()).isFalse();
    }

    @Test
    void getGamesByMonthReturnsMonthScopedResponse() {
        when(gameRepository.findByGameDateBetweenOrderByGameDateAscScheduledAtAscPublicGameIdAsc(
                eq(LocalDate.of(2026, 4, 1)),
                eq(LocalDate.of(2026, 4, 30))
        )).thenReturn(List.of());

        GamesByMonthResponse response = gameReadService.getGamesByMonth(YearMonth.of(2026, 4));

        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.month()).isEqualTo(4);
        assertThat(response.games()).isEmpty();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void getScoreboardDefaultsToCurrentKstDate() {
        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 9))))
                .thenReturn(List.of());

        ScoreboardResponse response = gameReadService.getScoreboard(null);

        assertThat(response.date()).isEqualTo(LocalDate.of(2026, 4, 9));
        assertThat(response.games()).isEmpty();
        assertThat(response.isStale()).isFalse();
    }

    @Test
    void getGameDetailMapsLatestLiveSnapshotState() {
        Team lg = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                "https://example.com/logos/lg.png"
        );
        Team ssg = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "ssg",
                "SSG Landers",
                "SSG",
                "SSG Landers",
                "https://example.com/logos/ssg.png"
        );
        Game game = new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260409-LG-SSG",
                "kbo",
                "20260409SKLG0",
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.LIVE,
                lg,
                ssg,
                4,
                3,
                "Top 8",
                false,
                false,
                null,
                null,
                null
        );
        setTimestamps(game,
                OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 4, 9, 20, 10, 0, 0, ZoneOffset.ofHours(9))
        );

        GameSnapshot snapshot = new GameSnapshot(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                game,
                8,
                "top",
                "Top 8",
                2,
                1,
                1,
                true,
                false,
                true,
                null,
                null,
                4,
                3,
                null,
                null,
                null,
                null,
                null,
                null,
                "hash",
                null,
                OffsetDateTime.of(2026, 4, 9, 20, 12, 0, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findByPublicGameId(eq("20260409-LG-SSG"))).thenReturn(java.util.Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(java.util.Optional.of(snapshot));

        GameDetailResponse response = gameReadService.getGameDetail("20260409-LG-SSG");

        assertThat(response.id()).isEqualTo("20260409-LG-SSG");
        assertThat(response.awayScore()).isEqualTo(3);
        assertThat(response.homeScore()).isEqualTo(4);
        assertThat(response.state()).isNotNull();
        assertThat(response.state().inning()).isEqualTo(8);
        assertThat(response.state().half()).isEqualTo("top");
        assertThat(response.state().bases().first()).isTrue();
        assertThat(response.state().bases().second()).isFalse();
        assertThat(response.state().bases().third()).isTrue();
        assertThat(response.cancelReason()).isNull();
        assertThat(response.updatedAt()).isEqualTo(OffsetDateTime.of(2026, 4, 9, 20, 12, 0, 0, ZoneOffset.ofHours(9)));
    }

    @Test
    void getGameLineScoreMapsPersistedInningsAndTotals() {
        Team lg = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                null
        );
        Team kia = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "kia",
                "KIA Tigers",
                "KIA",
                "KIA Tigers",
                null
        );
        Game game = new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260401-LG-KIA",
                "kbo",
                "20260401HTLG0",
                LocalDate.of(2026, 4, 1),
                OffsetDateTime.of(2026, 4, 1, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.FINAL,
                lg,
                kia,
                7,
                2,
                null,
                false,
                false,
                null,
                null,
                null
        );
        setTimestamps(game,
                OffsetDateTime.of(2026, 4, 1, 22, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 4, 1, 22, 0, 0, 0, ZoneOffset.ofHours(9))
        );
        GameSnapshot snapshot = new GameSnapshot(
                UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
                game,
                9,
                "top",
                "Top 9",
                0,
                0,
                3,
                true,
                true,
                true,
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
                "hash",
                null,
                OffsetDateTime.of(2026, 4, 9, 20, 12, 0, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findByPublicGameId(eq("20260401-LG-KIA"))).thenReturn(java.util.Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(java.util.Optional.of(snapshot));
        when(lineScoreRepository.findByGame_IdOrderByInningNumberAsc(eq(game.getId()))).thenReturn(List.of(
                new LineScore(UUID.randomUUID(), game, 1, 0, 3),
                new LineScore(UUID.randomUUID(), game, 5, 1, 0)
        ));

        GameLineScoreResponse response = gameReadService.getGameLineScore("20260401-LG-KIA");

        assertThat(response.gameId()).isEqualTo("20260401-LG-KIA");
        assertThat(response.innings()).hasSize(2);
        assertThat(response.innings().get(0).inning()).isEqualTo(1);
        assertThat(response.innings().get(0).awayRuns()).isEqualTo(0);
        assertThat(response.innings().get(0).homeRuns()).isEqualTo(3);
        assertThat(response.totals()).isNotNull();
        assertThat(response.totals().away().runs()).isEqualTo(2);
        assertThat(response.totals().away().hits()).isEqualTo(7);
        assertThat(response.totals().home().runs()).isEqualTo(7);
        assertThat(response.totals().home().balls()).isEqualTo(10);
    }

    @Test
    void getGameBoxscoreGroupsAwayAndHomeRecordsSortedBySourceOrder() {
        Team away = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "ssg",
                "SSG Landers",
                "SSG",
                "SSG Landers",
                null
        );
        Team home = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "kia",
                "KIA Tigers",
                "KIA",
                "KIA Tigers",
                null
        );
        Game game = new Game(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "20260510-SSG-KIA",
                "kbo",
                "20260510SKHT0",
                LocalDate.of(2026, 5, 10),
                OffsetDateTime.of(2026, 5, 10, 14, 0, 0, 0, ZoneOffset.ofHours(9)),
                "광주",
                GameStatus.FINAL,
                home,
                away,
                3,
                1,
                null,
                false,
                false,
                null,
                null,
                null
        );

        when(gameRepository.findByPublicGameId(eq("20260510-SSG-KIA"))).thenReturn(java.util.Optional.of(game));
        when(gameBoxscoreRecordReadRepository.findBatterRecords(eq(game.getId()))).thenReturn(List.of(
                new BatterRecordReadRow(home.getId(), 1, 2, "중", "박찬호", 4, 1, 2, 1, null, null, null, null, null, null, "0.300",
                        OffsetDateTime.of(2026, 5, 10, 17, 2, 0, 0, ZoneOffset.UTC)),
                new BatterRecordReadRow(away.getId(), 2, 3, "좌", "최정", 3, 0, 1, 0, null, null, null, null, null, null, "0.280",
                        OffsetDateTime.of(2026, 5, 10, 17, 1, 0, 0, ZoneOffset.UTC)),
                new BatterRecordReadRow(away.getId(), 0, 1, "유", "안상현", 3, 1, 0, 0, null, null, null, null, null, null, "0.300",
                        OffsetDateTime.of(2026, 5, 10, 17, 3, 0, 0, ZoneOffset.UTC))
        ));
        when(gameBoxscoreRecordReadRepository.findPitcherRecords(eq(game.getId()))).thenReturn(List.of(
                new PitcherRecordReadRow(home.getId(), 0, 1, "잭로그", "선발", "승", 1, 0, 0, "6 1/3", 24, 88, 22, 5, 0, 1, 6, 1, 1, "3.19",
                        OffsetDateTime.of(2026, 5, 10, 17, 4, 0, 0, ZoneOffset.UTC)),
                new PitcherRecordReadRow(away.getId(), 0, 1, "최민준", "선발", "패", 0, 1, 0, "2", 12, 46, 8, 3, 1, 3, 0, 3, 2, "3.23",
                        OffsetDateTime.of(2026, 5, 10, 17, 2, 0, 0, ZoneOffset.UTC))
        ));

        GameBoxscoreResponse response = gameReadService.getGameBoxscore("20260510-SSG-KIA");

        assertThat(response.gameId()).isEqualTo("20260510-SSG-KIA");
        assertThat(response.awayBatters()).extracting("playerName").containsExactly("안상현", "최정");
        assertThat(response.homeBatters()).extracting("playerName").containsExactly("박찬호");
        assertThat(response.awayBatters().get(0).sourceOrder()).isZero();
        assertThat(response.awayBatters().get(0).homeRuns()).isNull();
        assertThat(response.awayBatters().get(0).walks()).isNull();
        assertThat(response.awayBatters().get(0).strikeouts()).isNull();
        assertThat(response.awayPitchers()).extracting("playerName").containsExactly("최민준");
        assertThat(response.homePitchers()).extracting("playerName").containsExactly("잭로그");
        assertThat(response.awayPitchers().get(0).walksOrHitByPitch()).isEqualTo(3);
        assertThat(response.homePitchers().get(0).inningsPitched()).isEqualTo("6 1/3");
        assertThat(response.homePitchers().get(0).era()).isEqualTo("3.19");
        assertThat(response.updatedAt()).isEqualTo(OffsetDateTime.of(2026, 5, 11, 2, 4, 0, 0, ZoneOffset.ofHours(9)));
        assertThat(response.isStale()).isFalse();
    }

    @Test
    void getGameBoxscoreReturnsEmptyArraysWhenRecordsAreMissing() {
        Team away = new Team(UUID.randomUUID(), "lg", "LG Twins", "LG", "LG Twins", null);
        Team home = new Team(UUID.randomUUID(), "doosan", "Doosan Bears", "Doosan", "Doosan Bears", null);
        Game game = new Game(
                UUID.randomUUID(),
                "20260510-LG-DOO",
                "kbo",
                "20260510LGOB0",
                LocalDate.of(2026, 5, 10),
                OffsetDateTime.of(2026, 5, 10, 14, 0, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.FINAL,
                home,
                away,
                2,
                1,
                null,
                false,
                false,
                null,
                null,
                null
        );

        when(gameRepository.findByPublicGameId(eq("20260510-LG-DOO"))).thenReturn(java.util.Optional.of(game));
        when(gameBoxscoreRecordReadRepository.findBatterRecords(eq(game.getId()))).thenReturn(List.of());
        when(gameBoxscoreRecordReadRepository.findPitcherRecords(eq(game.getId()))).thenReturn(List.of());

        GameBoxscoreResponse response = gameReadService.getGameBoxscore("20260510-LG-DOO");

        assertThat(response.awayBatters()).isEmpty();
        assertThat(response.homeBatters()).isEmpty();
        assertThat(response.awayPitchers()).isEmpty();
        assertThat(response.homePitchers()).isEmpty();
        assertThat(response.updatedAt()).isNull();
    }

    @Test
    void getGamesByDateMapsCancelledGameWithKnownCancelReason() {
        Team kia = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "kia",
                "KIA Tigers",
                "KIA",
                "KIA Tigers",
                null
        );
        Team samsung = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "samsung",
                "Samsung Lions",
                "Samsung",
                "Samsung Lions",
                null
        );
        Game game = new Game(
                UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                "20260409-KIA-SAM",
                "kbo",
                "20260409SSHT0",
                LocalDate.of(2026, 4, 9),
                OffsetDateTime.of(2026, 4, 9, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "광주",
                GameStatus.CANCELLED,
                kia,
                samsung,
                null,
                null,
                null,
                true,
                false,
                GameCancelReason.RAIN,
                "우천취소",
                null
        );
        setTimestamps(game,
                OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 4, 9, 18, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(eq(LocalDate.of(2026, 4, 9))))
                .thenReturn(List.of(game));

        GamesByDateResponse response = gameReadService.getGamesByDate(LocalDate.of(2026, 4, 9));

        assertThat(response.games()).hasSize(1);
        assertThat(response.games().get(0).status()).isEqualTo("cancelled");
        assertThat(response.games().get(0).isCancelled()).isTrue();
        assertThat(response.games().get(0).isPostponed()).isFalse();
        assertThat(response.games().get(0).cancelReason()).isEqualTo("rain");
    }

    @Test
    void getGameDetailMapsCancelledGameWithUnknownCancelReason() {
        Team lg = new Team(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "lg",
                "LG Twins",
                "LG",
                "LG Twins",
                null
        );
        Team ssg = new Team(
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "ssg",
                "SSG Landers",
                "SSG",
                "SSG Landers",
                null
        );
        Game game = new Game(
                UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                "20260410-LG-SSG",
                "kbo",
                "20260410LGSS0",
                LocalDate.of(2026, 4, 10),
                OffsetDateTime.of(2026, 4, 10, 18, 30, 0, 0, ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.CANCELLED,
                lg,
                ssg,
                null,
                null,
                null,
                true,
                false,
                GameCancelReason.UNKNOWN,
                null,
                null
        );
        setTimestamps(game,
                OffsetDateTime.of(2026, 4, 10, 18, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 4, 10, 18, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        when(gameRepository.findByPublicGameId(eq("20260410-LG-SSG"))).thenReturn(java.util.Optional.of(game));
        when(gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(eq(game.getId())))
                .thenReturn(java.util.Optional.empty());

        GameDetailResponse response = gameReadService.getGameDetail("20260410-LG-SSG");

        assertThat(response.status()).isEqualTo("cancelled");
        assertThat(response.isCancelled()).isTrue();
        assertThat(response.cancelReason()).isEqualTo("unknown");
    }

    private void setTimestamps(Game game, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        try {
            java.lang.reflect.Field createdAtField = Game.class.getDeclaredField("createdAt");
            java.lang.reflect.Field updatedAtField = Game.class.getDeclaredField("updatedAt");
            createdAtField.setAccessible(true);
            updatedAtField.setAccessible(true);
            createdAtField.set(game, createdAt);
            updatedAtField.set(game, updatedAt);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to set timestamps for test fixture", exception);
        }
    }
}
