package com.kbo.crawlerapi.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.api.dto.GameBatterRecordDto;
import com.kbo.crawlerapi.api.dto.GameBoxscoreResponse;
import com.kbo.crawlerapi.api.dto.GameDetailResponse;
import com.kbo.crawlerapi.api.dto.GameLineScoreResponse;
import com.kbo.crawlerapi.api.dto.GamePitcherRecordDto;
import com.kbo.crawlerapi.api.dto.GameTotalsDto;
import com.kbo.crawlerapi.api.dto.GameStateDto;
import com.kbo.crawlerapi.api.dto.GameSummaryDto;
import com.kbo.crawlerapi.api.dto.GamesByDateResponse;
import com.kbo.crawlerapi.api.dto.GamesByMonthResponse;
import com.kbo.crawlerapi.api.dto.LineScoreInningDto;
import com.kbo.crawlerapi.api.dto.ScoreboardGameDto;
import com.kbo.crawlerapi.api.dto.ScoreboardResponse;
import com.kbo.crawlerapi.api.dto.TeamSummaryDto;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.LineScore;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository.BatterRecordReadRow;
import com.kbo.crawlerapi.repository.GameBoxscoreRecordReadRepository.PitcherRecordReadRow;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.LineScoreRepository;

@Service
public class GameReadService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final LineScoreRepository lineScoreRepository;
    private final GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository;
    private final Clock applicationClock;

    public GameReadService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository,
            Clock applicationClock
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.gameBoxscoreRecordReadRepository = gameBoxscoreRecordReadRepository;
        this.applicationClock = applicationClock;
    }

    public GamesByDateResponse getGamesByDate(LocalDate date) {
        List<Game> games = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(date);
        return new GamesByDateResponse(date, toGameSummaries(games), latestUpdatedAt(games), false);
    }

    public GamesByMonthResponse getGamesByMonth(YearMonth yearMonth) {
        List<Game> games = gameRepository.findByGameDateBetweenOrderByGameDateAscScheduledAtAscPublicGameIdAsc(
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth()
        );
        return new GamesByMonthResponse(yearMonth.getYear(), yearMonth.getMonthValue(), toGameSummaries(games), latestUpdatedAt(games), false);
    }

    public ScoreboardResponse getScoreboard(LocalDate date) {
        LocalDate targetDate = date != null ? date : LocalDate.now(applicationClock);
        List<Game> games = gameRepository.findByGameDateOrderByScheduledAtAscPublicGameIdAsc(targetDate);
        return new ScoreboardResponse(targetDate, games.stream().map(this::toScoreboardGame).toList(), latestUpdatedAt(games), false);
    }

    public GameDetailResponse getGameDetail(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        GameSnapshot latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);

        return new GameDetailResponse(
                game.getPublicGameId(),
                game.getProvider(),
                game.getProviderGameId(),
                game.getGameDate(),
                toKst(game.getScheduledAt()),
                game.getStadium(),
                game.getStatus().getApiValue(),
                game.isCancelled(),
                game.isPostponed(),
                toCancelReason(game),
                toTeamSummary(game.getAwayTeam()),
                toTeamSummary(game.getHomeTeam()),
                latestSnapshot != null && latestSnapshot.getAwayScore() != null ? latestSnapshot.getAwayScore() : game.getAwayScore(),
                latestSnapshot != null && latestSnapshot.getHomeScore() != null ? latestSnapshot.getHomeScore() : game.getHomeScore(),
                toGameState(game, latestSnapshot),
                null,
                null,
                null,
                latestUpdatedAt(game, latestSnapshot),
                toKst(latestSourceUpdatedAt(game, latestSnapshot)),
                false
        );
    }

    public GameLineScoreResponse getGameLineScore(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        GameSnapshot latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);
        List<LineScore> lineScores = lineScoreRepository.findByGame_IdOrderByInningNumberAsc(game.getId());

        return new GameLineScoreResponse(
                game.getPublicGameId(),
                lineScores.stream()
                        .map(lineScore -> new LineScoreInningDto(
                                lineScore.getInningNumber(),
                                lineScore.getAwayRuns(),
                                lineScore.getHomeRuns()
                        ))
                        .toList(),
                toTotals(latestSnapshot),
                latestUpdatedAt(game, latestSnapshot),
                false
        );
    }

    public GameBoxscoreResponse getGameBoxscore(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        List<BatterRecordReadRow> batterRows = gameBoxscoreRecordReadRepository.findBatterRecords(game.getId());
        List<PitcherRecordReadRow> pitcherRows = gameBoxscoreRecordReadRepository.findPitcherRecords(game.getId());

        return new GameBoxscoreResponse(
                game.getPublicGameId(),
                batterRows.stream()
                        .filter(row -> row.teamId().equals(game.getAwayTeam().getId()))
                        .sorted(Comparator.comparingInt(BatterRecordReadRow::sourceOrder))
                        .map(this::toBatterRecord)
                        .toList(),
                batterRows.stream()
                        .filter(row -> row.teamId().equals(game.getHomeTeam().getId()))
                        .sorted(Comparator.comparingInt(BatterRecordReadRow::sourceOrder))
                        .map(this::toBatterRecord)
                        .toList(),
                pitcherRows.stream()
                        .filter(row -> row.teamId().equals(game.getAwayTeam().getId()))
                        .sorted(Comparator.comparingInt(PitcherRecordReadRow::sourceOrder))
                        .map(this::toPitcherRecord)
                        .toList(),
                pitcherRows.stream()
                        .filter(row -> row.teamId().equals(game.getHomeTeam().getId()))
                        .sorted(Comparator.comparingInt(PitcherRecordReadRow::sourceOrder))
                        .map(this::toPitcherRecord)
                        .toList(),
                latestBoxscoreUpdatedAt(batterRows, pitcherRows),
                false
        );
    }

    private List<GameSummaryDto> toGameSummaries(List<Game> games) {
        return games.stream().map(this::toGameSummary).toList();
    }

    private GameSummaryDto toGameSummary(Game game) {
        return new GameSummaryDto(
                game.getPublicGameId(),
                game.getProvider(),
                game.getProviderGameId(),
                game.getGameDate(),
                toKst(game.getScheduledAt()),
                game.getStadium(),
                game.getStatus().getApiValue(),
                game.isCancelled(),
                game.isPostponed(),
                toCancelReason(game),
                toTeamSummary(game.getAwayTeam()),
                toTeamSummary(game.getHomeTeam()),
                game.getAwayScore(),
                game.getHomeScore(),
                toKst(game.getUpdatedAt()),
                toKst(game.getSourceUpdatedAt()),
                false
        );
    }

    private ScoreboardGameDto toScoreboardGame(Game game) {
        return new ScoreboardGameDto(
                game.getPublicGameId(),
                game.getStatus().getApiValue(),
                toCancelReason(game),
                toKst(game.getScheduledAt()),
                game.getStadium(),
                toTeamSummary(game.getAwayTeam()),
                toTeamSummary(game.getHomeTeam()),
                game.getAwayScore(),
                game.getHomeScore(),
                null
        );
    }

    private GameBatterRecordDto toBatterRecord(BatterRecordReadRow row) {
        return new GameBatterRecordDto(
                row.sourceOrder(),
                row.battingOrder(),
                row.position(),
                row.playerName(),
                row.atBats(),
                row.runs(),
                row.hits(),
                row.rbi(),
                row.homeRuns(),
                row.walks(),
                row.strikeouts(),
                row.stolenBases(),
                row.battingAverage()
        );
    }

    private GamePitcherRecordDto toPitcherRecord(PitcherRecordReadRow row) {
        return new GamePitcherRecordDto(
                row.sourceOrder(),
                row.pitchingOrder(),
                row.playerName(),
                row.appearance(),
                row.decisionResult(),
                row.wins(),
                row.losses(),
                row.saves(),
                row.inningsPitched(),
                row.battersFaced(),
                row.pitchCount(),
                row.atBats(),
                row.hits(),
                row.homeRuns(),
                row.walksOrHitByPitch(),
                row.strikeouts(),
                row.runs(),
                row.earnedRuns(),
                row.era()
        );
    }

    private GameStateDto toGameState(Game game, GameSnapshot latestSnapshot) {
        if (latestSnapshot == null || game.getStatus() != com.kbo.crawlerapi.domain.GameStatus.LIVE) {
            return null;
        }
        if (latestSnapshot.getInning() == null && latestSnapshot.getInningLabel() == null) {
            return null;
        }
        return new GameStateDto(
                latestSnapshot.getInning(),
                latestSnapshot.getInningHalf(),
                latestSnapshot.getInningLabel(),
                latestSnapshot.getBalls(),
                latestSnapshot.getStrikes(),
                latestSnapshot.getOuts(),
                new GameStateDto.BasesDto(
                        latestSnapshot.isRunnerOnFirst(),
                        latestSnapshot.isRunnerOnSecond(),
                        latestSnapshot.isRunnerOnThird()
                )
        );
    }

    private TeamSummaryDto toTeamSummary(com.kbo.crawlerapi.domain.Team team) {
        return new TeamSummaryDto(
                team.getTeamCode(),
                team.getName(),
                team.getShortName(),
                team.getLogoUrl()
        );
    }

    private String toCancelReason(Game game) {
        return game.getCancelReason() == null ? null : game.getCancelReason().getApiValue();
    }

    private GameTotalsDto toTotals(GameSnapshot latestSnapshot) {
        if (latestSnapshot == null) {
            return new GameTotalsDto(null, null);
        }
        GameTotalsDto.TeamTotalsDto awayTotals = toTeamTotals(
                latestSnapshot.getAwayScore(),
                latestSnapshot.getAwayHits(),
                latestSnapshot.getAwayErrors(),
                latestSnapshot.getAwayBalls()
        );
        GameTotalsDto.TeamTotalsDto homeTotals = toTeamTotals(
                latestSnapshot.getHomeScore(),
                latestSnapshot.getHomeHits(),
                latestSnapshot.getHomeErrors(),
                latestSnapshot.getHomeBalls()
        );
        if (awayTotals == null && homeTotals == null) {
            return new GameTotalsDto(null, null);
        }
        return new GameTotalsDto(awayTotals, homeTotals);
    }

    private GameTotalsDto.TeamTotalsDto toTeamTotals(Integer runs, Integer hits, Integer errors, Integer balls) {
        if (runs == null && hits == null && errors == null && balls == null) {
            return null;
        }
        return new GameTotalsDto.TeamTotalsDto(runs, hits, errors, balls);
    }

    private OffsetDateTime latestUpdatedAt(List<Game> games) {
        return games.stream()
                .map(Game::getUpdatedAt)
                .filter(updatedAt -> updatedAt != null)
                .max(Comparator.naturalOrder())
                .map(this::toKst)
                .orElse(null);
    }

    private OffsetDateTime latestUpdatedAt(Game game, GameSnapshot latestSnapshot) {
        OffsetDateTime latest = game.getUpdatedAt();
        if (latestSnapshot != null && latestSnapshot.getFetchedAt() != null
                && (latest == null || latestSnapshot.getFetchedAt().isAfter(latest))) {
            latest = latestSnapshot.getFetchedAt();
        }
        return toKst(latest);
    }

    private OffsetDateTime latestBoxscoreUpdatedAt(
            List<BatterRecordReadRow> batterRows,
            List<PitcherRecordReadRow> pitcherRows
    ) {
        return java.util.stream.Stream.concat(
                        batterRows.stream().map(BatterRecordReadRow::updatedAt),
                        pitcherRows.stream().map(PitcherRecordReadRow::updatedAt)
                )
                .filter(updatedAt -> updatedAt != null)
                .max(Comparator.naturalOrder())
                .map(this::toKst)
                .orElse(null);
    }

    private OffsetDateTime latestSourceUpdatedAt(Game game, GameSnapshot latestSnapshot) {
        if (latestSnapshot != null && latestSnapshot.getSourceUpdatedAt() != null) {
            return latestSnapshot.getSourceUpdatedAt();
        }
        return game.getSourceUpdatedAt();
    }

    private OffsetDateTime toKst(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
