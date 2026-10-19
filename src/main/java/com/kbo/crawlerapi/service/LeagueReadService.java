package com.kbo.crawlerapi.service;

import com.kbo.crawlerapi.api.dto.StandingsResponse;
import com.kbo.crawlerapi.api.dto.TeamDto;
import com.kbo.crawlerapi.api.dto.TeamStandingDto;
import com.kbo.crawlerapi.api.dto.TeamsResponse;
import com.kbo.crawlerapi.domain.Team;
import com.kbo.crawlerapi.repository.TeamRankReadRepository;
import com.kbo.crawlerapi.repository.TeamRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeagueReadService {

    private static final Duration STANDINGS_STALE_AFTER = Duration.ofHours(24);

    private final TeamRepository teamRepository;
    private final TeamRankReadRepository teamRankReadRepository;
    private final Clock applicationClock;

    public LeagueReadService(
            TeamRepository teamRepository,
            TeamRankReadRepository teamRankReadRepository,
            Clock applicationClock
    ) {
        this.teamRepository = teamRepository;
        this.teamRankReadRepository = teamRankReadRepository;
        this.applicationClock = applicationClock;
    }

    @Transactional(readOnly = true)
    public TeamsResponse getTeams() {
        List<TeamDto> teams = teamRepository.findAll().stream()
                .sorted(Comparator.comparing(Team::getTeamCode))
                .map(team -> new TeamDto(
                        team.getId(),
                        team.getTeamCode(),
                        team.getName(),
                        team.getShortName(),
                        team.getEnglishName(),
                        team.getLogoUrl(),
                        team.getUpdatedAt()
                ))
                .toList();
        OffsetDateTime latestUpdate = teams.stream()
                .map(TeamDto::updatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        return new TeamsResponse(teams, latestUpdate);
    }

    @Transactional(readOnly = true)
    public StandingsResponse getStandings(int season) {
        if (season < 1982 || season > 2100) {
            throw new IllegalArgumentException("season must be in the range 1982-2100");
        }
        List<TeamStandingDto> standings = teamRankReadRepository.findBySeason(season);
        OffsetDateTime updatedAt = standings.stream()
                .map(TeamStandingDto::updatedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        boolean stale = updatedAt != null
                && updatedAt.plus(STANDINGS_STALE_AFTER).isBefore(OffsetDateTime.now(applicationClock));
        return new StandingsResponse(season, standings, updatedAt, stale);
    }
}
