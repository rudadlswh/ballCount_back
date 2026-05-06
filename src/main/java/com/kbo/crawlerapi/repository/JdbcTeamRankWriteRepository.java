package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.service.TeamRankRow;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcTeamRankWriteRepository implements TeamRankWriteRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO kbo_crawler_api.team_ranks (
                season,
                team_id,
                rank,
                team_name,
                games_played,
                wins,
                losses,
                draws,
                winning_percentage,
                games_behind,
                streak_type,
                streak_count,
                streak_text,
                last_game_date,
                calculated_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (season, team_id) DO UPDATE SET
                rank = excluded.rank,
                team_name = excluded.team_name,
                games_played = excluded.games_played,
                wins = excluded.wins,
                losses = excluded.losses,
                draws = excluded.draws,
                winning_percentage = excluded.winning_percentage,
                games_behind = excluded.games_behind,
                streak_type = excluded.streak_type,
                streak_count = excluded.streak_count,
                streak_text = excluded.streak_text,
                last_game_date = excluded.last_game_date,
                calculated_at = excluded.calculated_at,
                updated_at = excluded.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTeamRankWriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertAll(List<TeamRankRow> rows) {
        int[][] updated = jdbcTemplate.batchUpdate(UPSERT_SQL, rows, 50, (ps, row) -> {
            ps.setInt(1, row.season());
            ps.setObject(2, row.teamId());
            ps.setInt(3, row.rank());
            ps.setString(4, row.teamName());
            ps.setInt(5, row.gamesPlayed());
            ps.setInt(6, row.wins());
            ps.setInt(7, row.losses());
            ps.setInt(8, row.draws());
            ps.setBigDecimal(9, row.winningPercentage());
            ps.setBigDecimal(10, row.gamesBehind());
            ps.setString(11, row.streakType());
            ps.setInt(12, row.streakCount());
            ps.setString(13, row.streakText());
            ps.setDate(14, row.lastGameDate() == null ? null : Date.valueOf(row.lastGameDate()));
            ps.setTimestamp(15, Timestamp.from(row.calculatedAt().toInstant()));
            ps.setTimestamp(16, Timestamp.from(row.updatedAt().toInstant()));
        });
        int count = 0;
        for (int[] batch : updated) {
            for (int batchCount : batch) {
                count += batchCount;
            }
        }
        return count;
    }
}
