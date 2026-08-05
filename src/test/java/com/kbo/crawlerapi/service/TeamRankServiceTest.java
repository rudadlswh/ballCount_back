package com.kbo.crawlerapi.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.TeamRankWriteRepository;
import com.kbo.crawlerapi.repository.TeamRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamRankServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-06T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    @Mock
    private GameRepository gameRepository;

    @Mock
    private TeamRepository teamRepository;

    private RecordingTeamRankWriteRepository teamRankWriteRepository;
    private TeamRankService service;
    private List<Team> teams;

    @BeforeEach
    void setUp() {
        teamRankWriteRepository = new RecordingTeamRankWriteRepository();
        service = new TeamRankService(gameRepository, teamRepository, teamRankWriteRepository, CLOCK);
        teams = teams();
        when(teamRepository.findAll()).thenReturn(teams);
    }

    @Test
    void calculatesRankingWithWinsLossesAndDraws() {
        List<Game> games = List.of(
                game("g1", date(4, 1), teams.get(0), teams.get(1), 5, 3),
                game("g2", date(4, 2), teams.get(0), teams.get(2), 2, 2),
                game("g3", date(4, 3), teams.get(1), teams.get(2), 1, 4)
        );

        List<TeamRankRow> rows = service.calculateRows(2026, games);

        TeamRankRow first = rowFor(rows, teams.get(0));
        TeamRankRow second = rowFor(rows, teams.get(1));
        TeamRankRow third = rowFor(rows, teams.get(2));
        assertThat(first.rank()).isEqualTo(1);
        assertThat(first.wins()).isEqualTo(1);
        assertThat(first.losses()).isZero();
        assertThat(first.draws()).isEqualTo(1);
        assertThat(second.losses()).isEqualTo(2);
        assertThat(third.wins()).isEqualTo(1);
        assertThat(third.draws()).isEqualTo(1);
    }

    @Test
    void calculatesWinningPercentageIgnoringDraws() {
        List<Game> games = List.of(
                game("g1", date(4, 1), teams.get(0), teams.get(1), 5, 3),
                game("g2", date(4, 2), teams.get(1), teams.get(0), 4, 2),
                game("g3", date(4, 3), teams.get(0), teams.get(2), 2, 2)
        );

        List<TeamRankRow> rows = service.calculateRows(2026, games);

        assertThat(rowFor(rows, teams.get(0)).winningPercentage()).isEqualByComparingTo(new BigDecimal("0.500"));
        assertThat(rowFor(rows, teams.get(2)).winningPercentage()).isEqualByComparingTo(new BigDecimal("0.000"));
    }

    @Test
    void calculatesGamesBehindFromLeaderWinsAndLosses() {
        List<Game> games = List.of(
                game("g1", date(4, 1), teams.get(0), teams.get(1), 5, 3),
                game("g2", date(4, 2), teams.get(0), teams.get(2), 6, 4),
                game("g3", date(4, 3), teams.get(3), teams.get(1), 7, 2)
        );

        List<TeamRankRow> rows = service.calculateRows(2026, games);

        assertThat(rowFor(rows, teams.get(0)).gamesBehind()).isEqualByComparingTo(new BigDecimal("0.0"));
        assertThat(rowFor(rows, teams.get(3)).gamesBehind()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(rowFor(rows, teams.get(1)).gamesBehind()).isEqualByComparingTo(new BigDecimal("2.0"));
    }

    @Test
    void calculatesStreakTypeCountAndText() {
        List<Game> games = List.of(
                game("g1", date(4, 1), teams.get(0), teams.get(1), 5, 3),
                game("g2", date(4, 2), teams.get(2), teams.get(0), 2, 4),
                game("g3", date(4, 3), teams.get(0), teams.get(3), 6, 1),
                game("g4", date(4, 4), teams.get(4), teams.get(5), 2, 2)
        );

        List<TeamRankRow> rows = service.calculateRows(2026, games);

        assertThat(rowFor(rows, teams.get(0)).streakType()).isEqualTo("WIN");
        assertThat(rowFor(rows, teams.get(0)).streakCount()).isEqualTo(3);
        assertThat(rowFor(rows, teams.get(0)).streakText()).isEqualTo("3승");
        assertThat(rowFor(rows, teams.get(5)).streakText()).isEqualTo("1무");
        assertThat(rowFor(rows, teams.get(6)).streakText()).isEqualTo("-");
    }

    @Test
    void calculatesPreviousRankBeforeLatestCompletedGameDay() {
        List<Game> games = List.of(
                game("g1", date(4, 1), teams.get(0), teams.get(1), 5, 3),
                game("g2", date(4, 2), teams.get(1), teams.get(2), 6, 2)
        );

        List<TeamRankRow> rows = service.calculateRows(2026, games);

        TeamRankRow risingTeam = rowFor(rows, teams.get(1));
        assertThat(risingTeam.previousRank()).isNotNull();
        assertThat(risingTeam.rank()).isLessThan(risingTeam.previousRank());
    }

    @Test
    void fullSeasonRecalculationUpsertsAllTenTeams() {
        List<Game> games = List.of(game("g1", date(4, 1), teams.get(0), teams.get(1), 5, 3));
        when(gameRepository.findByGameDateBetweenAndStatusOrderByGameDateAscScheduledAtAscPublicGameIdAsc(
                eq(LocalDate.of(2026, 1, 1)),
                eq(LocalDate.of(2026, 12, 31)),
                eq(GameStatus.FINAL)
        )).thenReturn(games);

        TeamRankService.TeamRankRefreshResult result = service.refreshSeasonRankings(2026);

        assertThat(result.completedGameCount()).isEqualTo(1);
        assertThat(result.rowCount()).isEqualTo(10);
        assertThat(teamRankWriteRepository.rows).hasSize(10);
        assertThat(teamRankWriteRepository.rows).extracting(TeamRankRow::season).containsOnly(2026);
    }

    private TeamRankRow rowFor(List<TeamRankRow> rows, Team team) {
        return rows.stream()
                .filter(row -> row.teamId().equals(team.getId()))
                .findFirst()
                .orElseThrow();
    }

    private List<Team> teams() {
        List<Team> result = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            result.add(new Team(
                    UUID.nameUUIDFromBytes(("team-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    "t" + i,
                    "Team " + i,
                    "T" + i,
                    "Team " + i,
                    null
            ));
        }
        return result;
    }

    private LocalDate date(int month, int day) {
        return LocalDate.of(2026, month, day);
    }

    private Game game(String id, LocalDate date, Team awayTeam, Team homeTeam, int awayScore, int homeScore) {
        return new Game(
                UUID.nameUUIDFromBytes(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                id,
                "kbo",
                id,
                date,
                OffsetDateTime.of(date, java.time.LocalTime.of(18, 30), ZoneOffset.ofHours(9)),
                "잠실",
                GameStatus.FINAL,
                homeTeam,
                awayTeam,
                homeScore,
                awayScore,
                "종료",
                false,
                false,
                null,
                null,
                null
        );
    }

    private static final class RecordingTeamRankWriteRepository implements TeamRankWriteRepository {

        private List<TeamRankRow> rows = List.of();

        @Override
        public int upsertAll(List<TeamRankRow> rows) {
            this.rows = List.copyOf(rows);
            return rows.size();
        }
    }
}
