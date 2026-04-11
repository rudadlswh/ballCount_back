package com.kbo.crawlerapi.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapGameDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapGameEventDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapNotificationDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapResponse;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapRunnerStateDto;
import com.kbo.crawlerapi.api.dto.CompatibilityBootstrapTeamDto;
import com.kbo.crawlerapi.domain.Game;
import com.kbo.crawlerapi.domain.GameSnapshot;
import com.kbo.crawlerapi.domain.GameStatus;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.GameRepository;
import com.kbo.crawlerapi.repository.GameSnapshotRepository;
import com.kbo.crawlerapi.repository.TeamRepository;

@Service
public class CompatibilityBootstrapService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String REGULAR_SEASON = "regular_season";

    private final GameRepository gameRepository;
    private final GameSnapshotRepository gameSnapshotRepository;
    private final TeamRepository teamRepository;
    private final Clock applicationClock;

    public CompatibilityBootstrapService(
            GameRepository gameRepository,
            GameSnapshotRepository gameSnapshotRepository,
            TeamRepository teamRepository,
            Clock applicationClock
    ) {
        this.gameRepository = gameRepository;
        this.gameSnapshotRepository = gameSnapshotRepository;
        this.teamRepository = teamRepository;
        this.applicationClock = applicationClock;
    }

    public CompatibilityBootstrapResponse getBootstrap() {
        LocalDate today = LocalDate.now(applicationClock);
        List<Game> allGames = gameRepository.findAllByOrderByGameDateAscScheduledAtAscPublicGameIdAsc();

        Map<UUID, GameSnapshot> todaysSnapshotsByGameId = new HashMap<>();
        allGames.stream()
                .filter(game -> game.getGameDate().equals(today))
                .forEach(game -> {
                    GameSnapshot latestSnapshot = findLatestSnapshotForGame(game);
                    if (latestSnapshot != null) {
                        todaysSnapshotsByGameId.put(game.getId(), latestSnapshot);
                    }
                });

        return new CompatibilityBootstrapResponse(
                teamRepository.findAll().stream()
                        .sorted((left, right) -> left.getTeamCode().compareTo(right.getTeamCode()))
                        .map(this::toTeamDto)
                        .toList(),
                allGames.stream()
                        .map(game -> toGameDto(game, todaysSnapshotsByGameId.get(game.getId())))
                        .toList(),
                List.<CompatibilityBootstrapNotificationDto>of(),
                null
        );
    }

    private GameSnapshot findLatestSnapshotForGame(Game game) {
        return gameSnapshotRepository.findTopByGame_IdOrderByFetchedAtDescCreatedAtDesc(game.getId())
                .orElse(null);
    }

    private CompatibilityBootstrapTeamDto toTeamDto(Team team) {
        return new CompatibilityBootstrapTeamDto(
                team.getTeamCode(),
                team.getName(),
                team.getShortName(),
                team.getEnglishName() != null ? team.getEnglishName() : team.getName(),
                team.getShortName(),
                null
        );
    }

    private CompatibilityBootstrapGameDto toGameDto(Game game, GameSnapshot latestSnapshot) {
        return new CompatibilityBootstrapGameDto(
                game.getId(),
                game.getProviderGameId(),
                toKst(game.getScheduledAt()),
                game.getStadium(),
                game.getAwayTeam().getTeamCode(),
                game.getHomeTeam().getTeamCode(),
                resolvedAwayScore(game, latestSnapshot),
                resolvedHomeScore(game, latestSnapshot),
                toStatusCode(game),
                toStatusText(game),
                REGULAR_SEASON,
                toInningText(game, latestSnapshot),
                toRunnerState(latestSnapshot),
                latestSnapshot != null ? latestSnapshot.getOuts() : null,
                null,
                List.<CompatibilityBootstrapGameEventDto>of(),
                null
        );
    }

    private Integer resolvedAwayScore(Game game, GameSnapshot latestSnapshot) {
        if (latestSnapshot != null && latestSnapshot.getAwayScore() != null) {
            return latestSnapshot.getAwayScore();
        }
        return game.getAwayScore();
    }

    private Integer resolvedHomeScore(Game game, GameSnapshot latestSnapshot) {
        if (latestSnapshot != null && latestSnapshot.getHomeScore() != null) {
            return latestSnapshot.getHomeScore();
        }
        return game.getHomeScore();
    }

    private String toStatusCode(Game game) {
        return switch (game.getStatus()) {
            case SCHEDULED -> "PRE";
            case LIVE -> "LIVE";
            case FINAL -> "FINAL";
            case POSTPONED, SUSPENDED -> "DELAY";
            case CANCELLED -> "CANCELLED";
            case UNKNOWN -> "PRE";
        };
    }

    private String toStatusText(Game game) {
        return switch (game.getStatus()) {
            case SCHEDULED -> "예정";
            case LIVE -> "LIVE";
            case FINAL -> "종료";
            case POSTPONED, SUSPENDED -> "우천 중단";
            case CANCELLED -> "취소";
            case UNKNOWN -> "예정";
        };
    }

    private String toInningText(Game game, GameSnapshot latestSnapshot) {
        if (latestSnapshot != null && latestSnapshot.getInningLabel() != null) {
            return latestSnapshot.getInningLabel();
        }
        if (game.getStatus() == GameStatus.FINAL) {
            return "종료";
        }
        if (game.getStatus() == GameStatus.CANCELLED) {
            return "취소";
        }
        return game.getInningState();
    }

    private CompatibilityBootstrapRunnerStateDto toRunnerState(GameSnapshot latestSnapshot) {
        if (latestSnapshot == null || latestSnapshot.getInning() == null) {
            return null;
        }
        return new CompatibilityBootstrapRunnerStateDto(
                latestSnapshot.isRunnerOnFirst(),
                latestSnapshot.isRunnerOnSecond(),
                latestSnapshot.isRunnerOnThird()
        );
    }

    private OffsetDateTime toKst(OffsetDateTime value) {
        return value == null ? null : value.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
