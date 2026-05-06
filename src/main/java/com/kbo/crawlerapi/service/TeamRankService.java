package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.TeamRankWriteRepository;
import com.kbo.crawlerapi.repository.TeamRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamRankService {

    private static final Logger log = LoggerFactory.getLogger(TeamRankService.class);

    private final GameRepository gameRepository;
    private final TeamRepository teamRepository;
    private final TeamRankWriteRepository teamRankWriteRepository;
    private final Clock applicationClock;

    public TeamRankService(
            GameRepository gameRepository,
            TeamRepository teamRepository,
            TeamRankWriteRepository teamRankWriteRepository,
            Clock applicationClock
    ) {
        this.gameRepository = gameRepository;
        this.teamRepository = teamRepository;
        this.teamRankWriteRepository = teamRankWriteRepository;
        this.applicationClock = applicationClock;
    }

    @Transactional
    public TeamRankRefreshResult refreshSeasonRankings(int season) {
        log.info("[TeamRank] refresh start season={}", season);
        try {
            List<Game> completedGames = completedRegularSeasonGames(season);
            log.info("[TeamRank] completed games count={}", completedGames.size());

            List<TeamRankRow> rows = calculateRows(season, completedGames);
            log.info("[TeamRank] calculated rows={}", rows.size());

            teamRankWriteRepository.upsertAll(rows);
            log.info("[TeamRank] upsert success season={} rows={}", season, rows.size());
            return new TeamRankRefreshResult(season, completedGames.size(), rows.size());
        } catch (RuntimeException exception) {
            log.warn("[TeamRank] refresh failed season={} error={}", season, exception.getMessage(), exception);
            throw exception;
        }
    }

    public void refreshSeasonRankingsSafely(int season) {
        try {
            refreshSeasonRankings(season);
        } catch (RuntimeException exception) {
            // Logged in refreshSeasonRankings; callers use this path when ranking must not break their workflow.
        }
    }

    List<TeamRankRow> calculateRows(int season, List<Game> completedGames) {
        Map<UUID, TeamAccumulator> accumulators = new HashMap<>();
        for (Team team : teamRepository.findAll()) {
            accumulators.put(team.getId(), new TeamAccumulator(team));
        }

        completedGames.stream()
                .filter(this::hasUsableFinalScore)
                .forEach(game -> applyGame(accumulators, game));

        List<TeamAccumulator> sorted = accumulators.values().stream()
                .sorted(Comparator
                        .comparing(TeamAccumulator::winningPercentage).reversed()
                        .thenComparing(accumulator -> accumulator.wins, Comparator.reverseOrder())
                        .thenComparing(accumulator -> accumulator.losses)
                        .thenComparing(accumulator -> accumulator.team.getName()))
                .toList();

        TeamAccumulator leader = sorted.isEmpty() ? null : sorted.get(0);
        OffsetDateTime now = OffsetDateTime.now(applicationClock);

        List<TeamRankRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            TeamAccumulator accumulator = sorted.get(i);
            rows.add(toRow(season, rankOf(sorted, accumulator, i + 1), accumulator, leader, now));
        }
        return rows;
    }

    private List<Game> completedRegularSeasonGames(int season) {
        LocalDate start = LocalDate.of(season, 1, 1);
        LocalDate end = LocalDate.of(season, 12, 31);
        return gameRepository.findByGameDateBetweenAndStatusOrderByGameDateAscScheduledAtAscPublicGameIdAsc(
                        start,
                        end,
                        GameStatus.FINAL
                )
                .stream()
                .filter(game -> !game.isCancelled())
                .filter(game -> !game.isPostponed())
                .filter(this::hasUsableFinalScore)
                .toList();
    }

    private boolean hasUsableFinalScore(Game game) {
        return game.getStatus() == GameStatus.FINAL
                && game.getAwayScore() != null
                && game.getHomeScore() != null;
    }

    private void applyGame(Map<UUID, TeamAccumulator> accumulators, Game game) {
        TeamAccumulator away = accumulators.get(game.getAwayTeam().getId());
        TeamAccumulator home = accumulators.get(game.getHomeTeam().getId());
        if (away == null || home == null) {
            return;
        }

        int awayScore = game.getAwayScore();
        int homeScore = game.getHomeScore();
        if (awayScore > homeScore) {
            away.record(game.getGameDate(), StreakType.WIN);
            home.record(game.getGameDate(), StreakType.LOSS);
        } else if (homeScore > awayScore) {
            away.record(game.getGameDate(), StreakType.LOSS);
            home.record(game.getGameDate(), StreakType.WIN);
        } else {
            away.record(game.getGameDate(), StreakType.DRAW);
            home.record(game.getGameDate(), StreakType.DRAW);
        }
    }

    private int rankOf(List<TeamAccumulator> sorted, TeamAccumulator accumulator, int fallbackRank) {
        for (int i = 0; i < sorted.size(); i++) {
            TeamAccumulator candidate = sorted.get(i);
            if (candidate.winningPercentage().compareTo(accumulator.winningPercentage()) == 0
                    && candidate.wins == accumulator.wins
                    && candidate.losses == accumulator.losses) {
                return i + 1;
            }
        }
        return fallbackRank;
    }

    private TeamRankRow toRow(
            int season,
            int rank,
            TeamAccumulator accumulator,
            TeamAccumulator leader,
            OffsetDateTime now
    ) {
        return new TeamRankRow(
                season,
                accumulator.team.getId(),
                rank,
                accumulator.team.getName(),
                accumulator.gamesPlayed(),
                accumulator.wins,
                accumulator.losses,
                accumulator.draws,
                accumulator.winningPercentage(),
                gamesBehind(accumulator, leader),
                accumulator.streakType.name(),
                accumulator.streakCount,
                accumulator.streakText(),
                accumulator.lastGameDate,
                now,
                now
        );
    }

    private BigDecimal gamesBehind(TeamAccumulator accumulator, TeamAccumulator leader) {
        if (leader == null) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        BigDecimal value = BigDecimal.valueOf(
                (leader.wins - accumulator.wins + accumulator.losses - leader.losses) / 2.0
        );
        return value.setScale(1, RoundingMode.HALF_UP);
    }

    public record TeamRankRefreshResult(int season, int completedGameCount, int rowCount) {
    }

    private enum StreakType {
        WIN("승"),
        LOSS("패"),
        DRAW("무"),
        NONE("");

        private final String label;

        StreakType(String label) {
            this.label = label;
        }
    }

    private static final class TeamAccumulator {

        private final Team team;
        private int wins;
        private int losses;
        private int draws;
        private StreakType streakType = StreakType.NONE;
        private int streakCount;
        private LocalDate lastGameDate;

        private TeamAccumulator(Team team) {
            this.team = team;
        }

        private void record(LocalDate gameDate, StreakType result) {
            if (result == StreakType.WIN) {
                wins++;
            } else if (result == StreakType.LOSS) {
                losses++;
            } else if (result == StreakType.DRAW) {
                draws++;
            }

            if (lastGameDate == null || !gameDate.isBefore(lastGameDate)) {
                if (result == streakType) {
                    streakCount++;
                } else {
                    streakType = result;
                    streakCount = 1;
                }
                lastGameDate = gameDate;
            }
        }

        private int gamesPlayed() {
            return wins + losses + draws;
        }

        private BigDecimal winningPercentage() {
            int decisions = wins + losses;
            if (decisions == 0) {
                return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
            }
            return BigDecimal.valueOf(wins)
                    .divide(BigDecimal.valueOf(decisions), 3, RoundingMode.HALF_UP);
        }

        private String streakText() {
            if (streakType == StreakType.NONE || streakCount == 0) {
                return "-";
            }
            return streakCount + streakType.label;
        }
    }
}
