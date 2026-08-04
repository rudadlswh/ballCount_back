package com.kbo.crawlerapi.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.kbo.crawlerapi.api.ResourceNotFoundException;
import com.kbo.crawlerapi.api.dto.GameBatterRecordDto;
import com.kbo.crawlerapi.api.dto.GameBoxscoreResponse;
import com.kbo.crawlerapi.api.dto.GameDetailResponse;
import com.kbo.crawlerapi.api.dto.GameDetailDataResponse;
import com.kbo.crawlerapi.api.dto.GameLineScoreResponse;
import com.kbo.crawlerapi.api.dto.GameLineupResponse;
import com.kbo.crawlerapi.api.dto.GameLiveStateResponse;
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
import com.kbo.crawlerapi.support.HashSupport;

@Service
public class GameReadService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Logger log = LoggerFactory.getLogger(GameReadService.class);

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final LineScoreRepository lineScoreRepository;
    private final GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository;
    private final Clock applicationClock;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    public GameReadService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository,
            Clock applicationClock,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.lineScoreRepository = lineScoreRepository;
        this.gameBoxscoreRecordReadRepository = gameBoxscoreRecordReadRepository;
        this.applicationClock = applicationClock;
        this.objectMapper = objectMapper;
    }

    public GameReadService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            LineScoreRepository lineScoreRepository,
            GameBoxscoreRecordReadRepository gameBoxscoreRecordReadRepository,
            Clock applicationClock
    ) {
        this(
                gameRepository,
                gameSnapshotRepository,
                lineScoreRepository,
                gameBoxscoreRecordReadRepository,
                applicationClock,
                new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
        );
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
        List<PitcherRecordReadRow> pitcherRows = gameBoxscoreRecordReadRepository.findPitcherRecords(game.getId());
        return toGameDetailResponse(game, latestSnapshot, pitcherRows, new LinkedHashSet<>());
    }

    public GameDetailDataResponse getGameDetailData(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        GameSnapshot latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);
        List<LineScore> lineScores = lineScoreRepository.findByGame_IdOrderByInningNumberAsc(game.getId());
        List<BatterRecordReadRow> batterRows = gameBoxscoreRecordReadRepository.findBatterRecords(game.getId());
        List<PitcherRecordReadRow> pitcherRows = gameBoxscoreRecordReadRepository.findPitcherRecords(game.getId());
        ParsedLineup lineup = parseLineup(game, gameId);
        Set<String> appliedFallbacks = new LinkedHashSet<>();

        GameDetailResponse detail = toGameDetailResponse(game, latestSnapshot, pitcherRows, appliedFallbacks);
        GameLiveStateResponse liveState = toLiveStateResponse(game, latestSnapshot);
        GameLineScoreResponse lineScore = toGameLineScoreResponse(game, latestSnapshot, lineScores, batterRows, appliedFallbacks);
        GameBoxscoreResponse boxscore = toGameBoxscoreResponse(game, batterRows, pitcherRows, lineup, appliedFallbacks);
        GameLineupResponse lineupResponse = toGameLineupResponse(game, lineup);
        if (lineup.invalid()) {
            appliedFallbacks.add("lineup:empty-invalid-json");
        }

        List<String> unavailableSections = new ArrayList<>();
        if (lineScore.innings().isEmpty()) {
            unavailableSections.add("lineScore");
        }
        if (boxscore.awayBatters().isEmpty() && boxscore.homeBatters().isEmpty()
                && boxscore.awayPitchers().isEmpty() && boxscore.homePitchers().isEmpty()) {
            unavailableSections.add("boxscore");
        }
        if (lineupResponse.away().isEmpty() && lineupResponse.home().isEmpty()) {
            unavailableSections.add("lineup");
        }

        OffsetDateTime updatedAt = latest(
                detail.updatedAt(),
                lineScore.updatedAt(),
                boxscore.updatedAt(),
                lineupResponse.updatedAt()
        );
        return new GameDetailDataResponse(
                detail,
                liveState,
                lineScore,
                boxscore,
                lineupResponse,
                List.copyOf(appliedFallbacks),
                List.copyOf(unavailableSections),
                updatedAt,
                false
        );
    }

    private GameDetailResponse toGameDetailResponse(
            Game game,
            GameSnapshot latestSnapshot,
            List<PitcherRecordReadRow> pitcherRows,
            Set<String> appliedFallbacks
    ) {
        String awayStarter = preferredStartingPitcher(game.getAwayStartingPitcherName(), pitcherRows, game.getAwayTeam().getId());
        String homeStarter = preferredStartingPitcher(game.getHomeStartingPitcherName(), pitcherRows, game.getHomeTeam().getId());
        addFallbackIfDerived(appliedFallbacks, game.getAwayStartingPitcherName(), awayStarter, "awayStartingPitcherName:boxscore");
        addFallbackIfDerived(appliedFallbacks, game.getHomeStartingPitcherName(), homeStarter, "homeStartingPitcherName:boxscore");

        String winningPitcher = decisionPitcher(pitcherRows, "승");
        String losingPitcher = decisionPitcher(pitcherRows, "패");
        String savePitcher = decisionPitcher(pitcherRows, "세");
        if (winningPitcher != null) appliedFallbacks.add("winningPitcher:boxscore");
        if (losingPitcher != null) appliedFallbacks.add("losingPitcher:boxscore");
        if (savePitcher != null) appliedFallbacks.add("savePitcher:boxscore");

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
                awayStarter,
                homeStarter,
                toGameState(game, latestSnapshot),
                winningPitcher,
                losingPitcher,
                savePitcher,
                latest(latestUpdatedAt(game, latestSnapshot), latestBoxscoreUpdatedAt(List.of(), pitcherRows)),
                toKst(latestSourceUpdatedAt(game, latestSnapshot)),
                false
        );
    }

    public GameLiveStateResponse getGameLiveState(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        GameSnapshot latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);
        return toLiveStateResponse(game, latestSnapshot);
    }

    public Optional<GameLiveStateResponse> getLatestSnapshotLiveState(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        return gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .map(snapshot -> toLiveStateResponse(game, snapshot));
    }

    private GameLiveStateResponse toLiveStateResponse(Game game, GameSnapshot latestSnapshot) {
        Integer awayScore = latestSnapshot != null && latestSnapshot.getAwayScore() != null ? latestSnapshot.getAwayScore() : game.getAwayScore();
        Integer homeScore = latestSnapshot != null && latestSnapshot.getHomeScore() != null ? latestSnapshot.getHomeScore() : game.getHomeScore();
        return new GameLiveStateResponse(
                game.getPublicGameId(),
                game.getStatus().getApiValue(),
                latestSnapshot == null ? null : latestSnapshot.getInning(),
                latestSnapshot == null ? null : latestSnapshot.getInningHalf(),
                awayScore,
                homeScore,
                latestSnapshot == null ? null : latestSnapshot.getBalls(),
                latestSnapshot == null ? null : latestSnapshot.getStrikes(),
                latestSnapshot == null ? null : latestSnapshot.getOuts(),
                new GameLiveStateResponse.BasesDto(
                        latestSnapshot != null && latestSnapshot.isRunnerOnFirst(),
                        latestSnapshot != null && latestSnapshot.isRunnerOnSecond(),
                        latestSnapshot != null && latestSnapshot.isRunnerOnThird()
                ),
                new GameLiveStateResponse.BaseRunnersDto(
                        latestSnapshot == null ? null : latestSnapshot.getFirstBaseRunnerName(),
                        latestSnapshot == null ? null : latestSnapshot.getSecondBaseRunnerName(),
                        latestSnapshot == null ? null : latestSnapshot.getThirdBaseRunnerName()
                ),
                latestSnapshot == null ? null : latestSnapshot.getCurrentPitcherName(),
                latestSnapshot == null ? null : latestSnapshot.getCurrentBatterName(),
                liveStateHash(game, latestSnapshot, awayScore, homeScore),
                latestUpdatedAt(game, latestSnapshot)
        );
    }

    public GameLineScoreResponse getGameLineScore(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        GameSnapshot latestSnapshot = gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);
        List<LineScore> lineScores = lineScoreRepository.findByGame_IdOrderByInningNumberAsc(game.getId());
        List<BatterRecordReadRow> batterRows = gameBoxscoreRecordReadRepository.findBatterRecords(game.getId());

        return toGameLineScoreResponse(game, latestSnapshot, lineScores, batterRows, new LinkedHashSet<>());
    }

    private GameLineScoreResponse toGameLineScoreResponse(
            Game game,
            GameSnapshot latestSnapshot,
            List<LineScore> lineScores,
            List<BatterRecordReadRow> batterRows,
            Set<String> appliedFallbacks
    ) {
        return new GameLineScoreResponse(
                game.getPublicGameId(),
                lineScores.stream()
                        .map(lineScore -> new LineScoreInningDto(
                                lineScore.getInningNumber(),
                                lineScore.getAwayRuns(),
                                lineScore.getHomeRuns()
                        ))
                        .toList(),
                toTotals(latestSnapshot, game, batterRows, appliedFallbacks),
                latest(latestUpdatedAt(game, latestSnapshot), latestBoxscoreUpdatedAt(batterRows, List.of())),
                false
        );
    }

    public GameBoxscoreResponse getGameBoxscore(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        List<BatterRecordReadRow> batterRows = gameBoxscoreRecordReadRepository.findBatterRecords(game.getId());
        List<PitcherRecordReadRow> pitcherRows = gameBoxscoreRecordReadRepository.findPitcherRecords(game.getId());
        ParsedLineup lineup = parseLineup(game, gameId);

        return toGameBoxscoreResponse(game, batterRows, pitcherRows, lineup, new LinkedHashSet<>());
    }

    private GameBoxscoreResponse toGameBoxscoreResponse(
            Game game,
            List<BatterRecordReadRow> batterRows,
            List<PitcherRecordReadRow> pitcherRows,
            ParsedLineup lineup,
            Set<String> appliedFallbacks
    ) {
        return new GameBoxscoreResponse(
                game.getPublicGameId(),
                batterRows.stream()
                        .filter(row -> row.teamId().equals(game.getAwayTeam().getId()))
                        .sorted(Comparator.comparingInt(BatterRecordReadRow::sourceOrder))
                        .map(row -> toBatterRecord(row, lineup.away(), appliedFallbacks))
                        .toList(),
                batterRows.stream()
                        .filter(row -> row.teamId().equals(game.getHomeTeam().getId()))
                        .sorted(Comparator.comparingInt(BatterRecordReadRow::sourceOrder))
                        .map(row -> toBatterRecord(row, lineup.home(), appliedFallbacks))
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

    public GameLineupResponse getGameLineup(String gameId) {
        Game game = gameRepository.findByPublicGameId(gameId)
                .orElseThrow(() -> new ResourceNotFoundException("Game not found: " + gameId));
        return toGameLineupResponse(game, parseLineup(game, gameId));
    }

    private GameLineupResponse toGameLineupResponse(Game game, ParsedLineup lineup) {
        return new GameLineupResponse(
                game.getPublicGameId(),
                lineup.away(),
                lineup.home(),
                lineup.rawHash(),
                toKst(game.getUpdatedAt()),
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
                game.getAwayStartingPitcherName(),
                game.getHomeStartingPitcherName(),
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

    private GameBatterRecordDto toBatterRecord(
            BatterRecordReadRow row,
            JsonNode lineup,
            Set<String> appliedFallbacks
    ) {
        String position = text(row.position());
        if (position == null) {
            position = lineupPosition(lineup, row.playerName(), row.battingOrder());
            if (position != null) {
                appliedFallbacks.add("boxscore.batterPosition:lineup");
            }
        }
        return new GameBatterRecordDto(
                row.sourceOrder(),
                row.battingOrder(),
                position,
                row.playerName(),
                row.atBats(),
                row.runs(),
                row.hits(),
                row.rbi(),
                row.homeRuns(),
                row.walks(),
                row.strikeouts(),
                row.stolenBases(),
                row.groundedIntoDoublePlay(),
                row.errors(),
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

    private GameTotalsDto toTotals(
            GameSnapshot latestSnapshot,
            Game game,
            List<BatterRecordReadRow> batterRows,
            Set<String> appliedFallbacks
    ) {
        GameTotalsDto.TeamTotalsDto awayTotals = toTeamTotals(
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getAwayScore(), game.getAwayScore(), appliedFallbacks, "lineScore.awayRuns:game"),
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getAwayHits(), sumBatterValue(batterRows, game.getAwayTeam().getId(), BatterRecordReadRow::hits), appliedFallbacks, "lineScore.awayHits:boxscore"),
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getAwayErrors(), sumBatterValue(batterRows, game.getAwayTeam().getId(), BatterRecordReadRow::errors), appliedFallbacks, "lineScore.awayErrors:boxscore"),
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getAwayBalls(), sumBatterValue(batterRows, game.getAwayTeam().getId(), BatterRecordReadRow::walks), appliedFallbacks, "lineScore.awayBalls:boxscore")
        );
        GameTotalsDto.TeamTotalsDto homeTotals = toTeamTotals(
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getHomeScore(), game.getHomeScore(), appliedFallbacks, "lineScore.homeRuns:game"),
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getHomeHits(), sumBatterValue(batterRows, game.getHomeTeam().getId(), BatterRecordReadRow::hits), appliedFallbacks, "lineScore.homeHits:boxscore"),
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getHomeErrors(), sumBatterValue(batterRows, game.getHomeTeam().getId(), BatterRecordReadRow::errors), appliedFallbacks, "lineScore.homeErrors:boxscore"),
                firstNonNull(latestSnapshot == null ? null : latestSnapshot.getHomeBalls(), sumBatterValue(batterRows, game.getHomeTeam().getId(), BatterRecordReadRow::walks), appliedFallbacks, "lineScore.homeBalls:boxscore")
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

    private ParsedLineup parseLineup(Game game, String gameId) {
        JsonNode empty = objectMapper.createArrayNode();
        if (game.getLineupData() == null || game.getLineupData().isBlank()) {
            return new ParsedLineup(empty, objectMapper.createArrayNode(), null, false);
        }
        try {
            JsonNode root = objectMapper.readTree(game.getLineupData());
            if (root == null || !root.isObject()) {
                log.warn("Stored lineup data is not an object; returning empty lineup gameId={}", gameId);
                return new ParsedLineup(empty, objectMapper.createArrayNode(), null, true);
            }
            JsonNode away = root.path("away");
            JsonNode home = root.path("home");
            boolean invalid = !away.isArray() || !home.isArray();
            if (invalid) {
                log.warn("Stored lineup arrays are invalid; invalid sides fall back to empty arrays gameId={}", gameId);
            }
            return new ParsedLineup(
                    away.isArray() ? away : objectMapper.createArrayNode(),
                    home.isArray() ? home : objectMapper.createArrayNode(),
                    text(root.path("rawHash").asText(null)),
                    invalid
            );
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("Stored lineup data is invalid; returning empty lineup gameId={}", gameId);
            return new ParsedLineup(empty, objectMapper.createArrayNode(), null, true);
        }
    }

    private String lineupPosition(JsonNode lineup, String playerName, Integer battingOrder) {
        if (lineup == null || !lineup.isArray()) {
            return null;
        }
        String normalizedName = text(playerName);
        for (JsonNode player : lineup) {
            if (normalizedName != null && normalizedName.equals(text(player.path("name").asText(null)))) {
                return text(player.path("position").asText(null));
            }
        }
        if (battingOrder == null) {
            return null;
        }
        String matchedPosition = null;
        int matches = 0;
        for (JsonNode player : lineup) {
            String rawOrder = text(player.path("battingOrder").asText(null));
            if (rawOrder != null && rawOrder.equals(String.valueOf(battingOrder))) {
                String candidate = text(player.path("position").asText(null));
                if (candidate != null) {
                    matchedPosition = candidate;
                    matches++;
                }
            }
        }
        return matches == 1 ? matchedPosition : null;
    }

    private String preferredStartingPitcher(String stored, List<PitcherRecordReadRow> rows, UUID teamId) {
        String normalizedStored = text(stored);
        if (normalizedStored != null) {
            return normalizedStored;
        }
        return rows.stream()
                .filter(row -> row.teamId().equals(teamId))
                .sorted(Comparator
                        .comparingInt((PitcherRecordReadRow row) -> isStartingPitcher(row) ? 0 : 1)
                        .thenComparing(row -> row.pitchingOrder() == null ? Integer.MAX_VALUE : row.pitchingOrder())
                        .thenComparingInt(PitcherRecordReadRow::sourceOrder))
                .map(PitcherRecordReadRow::playerName)
                .map(this::text)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private boolean isStartingPitcher(PitcherRecordReadRow row) {
        String appearance = text(row.appearance());
        return (appearance != null && appearance.contains("선발")) || Integer.valueOf(1).equals(row.pitchingOrder());
    }

    private String decisionPitcher(List<PitcherRecordReadRow> rows, String decisionPrefix) {
        return rows.stream()
                .filter(row -> {
                    String decision = text(row.decisionResult());
                    return decision != null && decision.startsWith(decisionPrefix);
                })
                .sorted(Comparator.comparingInt(PitcherRecordReadRow::sourceOrder))
                .map(PitcherRecordReadRow::playerName)
                .map(this::text)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
    }

    private void addFallbackIfDerived(Set<String> appliedFallbacks, String primary, String resolved, String label) {
        if (text(primary) == null && text(resolved) != null) {
            appliedFallbacks.add(label);
        }
    }

    private Integer firstNonNull(Integer primary, Integer fallback, Set<String> appliedFallbacks, String label) {
        if (primary != null) {
            return primary;
        }
        if (fallback != null) {
            appliedFallbacks.add(label);
        }
        return fallback;
    }

    private Integer sumBatterValue(
            List<BatterRecordReadRow> rows,
            UUID teamId,
            Function<BatterRecordReadRow, Integer> value
    ) {
        List<Integer> values = rows.stream()
                .filter(row -> row.teamId().equals(teamId))
                .map(value)
                .filter(item -> item != null)
                .toList();
        return values.isEmpty() ? null : values.stream().mapToInt(Integer::intValue).sum();
    }

    private OffsetDateTime latest(OffsetDateTime... values) {
        OffsetDateTime latest = null;
        for (OffsetDateTime value : values) {
            if (value != null && (latest == null || value.isAfter(latest))) {
                latest = value;
            }
        }
        return latest;
    }

    private String text(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
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

    private String liveStateHash(Game game, GameSnapshot snapshot, Integer awayScore, Integer homeScore) {
        String sourceHash = snapshot == null || snapshot.getRawHash() == null ? "" : snapshot.getRawHash();
        String value = String.join("|",
                sourceHash,
                game.getPublicGameId(),
                game.getStatus().getApiValue(),
                String.valueOf(snapshot == null ? null : snapshot.getInning()),
                String.valueOf(snapshot == null ? null : snapshot.getInningHalf()),
                String.valueOf(awayScore),
                String.valueOf(homeScore),
                String.valueOf(snapshot == null ? null : snapshot.getBalls()),
                String.valueOf(snapshot == null ? null : snapshot.getStrikes()),
                String.valueOf(snapshot == null ? null : snapshot.getOuts()),
                String.valueOf(snapshot != null && snapshot.isRunnerOnFirst()),
                String.valueOf(snapshot != null && snapshot.isRunnerOnSecond()),
                String.valueOf(snapshot != null && snapshot.isRunnerOnThird()),
                String.valueOf(snapshot == null ? null : snapshot.getCurrentPitcherName()),
                String.valueOf(snapshot == null ? null : snapshot.getCurrentBatterName())
        );
        return HashSupport.sha256Hex(value);
    }

    private record ParsedLineup(JsonNode away, JsonNode home, String rawHash, boolean invalid) {
    }
}
