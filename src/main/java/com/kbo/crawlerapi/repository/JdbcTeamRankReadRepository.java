package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.api.dto.TeamStandingDto;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTeamRankReadRepository implements TeamRankReadRepository {

    private static final String SELECT_BY_SEASON = """
            SELECT
                ranks.season,
                ranks.team_id,
                teams.team_code,
                ranks.team_name,
                ranks.rank,
                ranks.previous_rank,
                ranks.games_played,
                ranks.wins,
                ranks.losses,
                ranks.draws,
                ranks.winning_percentage,
                ranks.games_behind,
                ranks.streak_type,
                ranks.streak_count,
                ranks.streak_text,
                ranks.last_game_date,
                ranks.calculated_at,
                ranks.updated_at
            FROM team_ranks ranks
            JOIN teams ON teams.id = ranks.team_id
            WHERE ranks.season = ?
            ORDER BY ranks.rank ASC NULLS LAST, teams.team_code ASC
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTeamRankReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TeamStandingDto> findBySeason(int season) {
        return jdbcTemplate.query(SELECT_BY_SEASON, (resultSet, rowNumber) -> new TeamStandingDto(
                resultSet.getInt("season"),
                resultSet.getObject("team_id", java.util.UUID.class),
                resultSet.getString("team_code"),
                resultSet.getString("team_name"),
                resultSet.getInt("rank"),
                resultSet.getObject("previous_rank", Integer.class),
                resultSet.getInt("games_played"),
                resultSet.getInt("wins"),
                resultSet.getInt("losses"),
                resultSet.getInt("draws"),
                resultSet.getBigDecimal("winning_percentage"),
                resultSet.getBigDecimal("games_behind"),
                resultSet.getString("streak_type"),
                resultSet.getInt("streak_count"),
                resultSet.getString("streak_text"),
                resultSet.getObject("last_game_date", java.time.LocalDate.class),
                resultSet.getObject("calculated_at", java.time.OffsetDateTime.class),
                resultSet.getObject("updated_at", java.time.OffsetDateTime.class)
        ), season);
    }
}
