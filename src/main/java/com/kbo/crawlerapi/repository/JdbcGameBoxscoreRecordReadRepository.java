package com.kbo.crawlerapi.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGameBoxscoreRecordReadRepository implements GameBoxscoreRecordReadRepository {

    private static final String BATTER_SELECT_SQL = """
            SELECT
                team_id,
                source_order,
                batting_order,
                position,
                player_name,
                at_bats,
                runs,
                hits,
                rbi,
                home_runs,
                walks,
                strikeouts,
                stolen_bases,
                grounded_into_double_play,
                errors,
                batting_average,
                updated_at
            FROM kbo_crawler_api.game_batter_records
            WHERE game_id = ?
            ORDER BY source_order ASC
            """;

    private static final String PITCHER_SELECT_SQL = """
            SELECT
                team_id,
                source_order,
                pitching_order,
                player_name,
                appearance,
                decision_result,
                wins,
                losses,
                saves,
                innings_pitched,
                batters_faced,
                pitch_count,
                at_bats,
                hits,
                home_runs,
                walks_or_hit_by_pitch,
                strikeouts,
                runs,
                earned_runs,
                era,
                updated_at
            FROM kbo_crawler_api.game_pitcher_records
            WHERE game_id = ?
            ORDER BY source_order ASC
            """;

    private static final String BATTER_COUNT_SQL = """
            SELECT count(*)
            FROM kbo_crawler_api.game_batter_records
            WHERE game_id = ?
            """;

    private static final String PITCHER_COUNT_SQL = """
            SELECT count(*)
            FROM kbo_crawler_api.game_pitcher_records
            WHERE game_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcGameBoxscoreRecordReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<BatterRecordReadRow> findBatterRecords(UUID gameId) {
        return jdbcTemplate.query(BATTER_SELECT_SQL, (rs, rowNum) -> mapBatter(rs), gameId);
    }

    @Override
    public List<PitcherRecordReadRow> findPitcherRecords(UUID gameId) {
        return jdbcTemplate.query(PITCHER_SELECT_SQL, (rs, rowNum) -> mapPitcher(rs), gameId);
    }

    @Override
    public long countBatterRecords(UUID gameId) {
        Long count = jdbcTemplate.queryForObject(BATTER_COUNT_SQL, Long.class, gameId);
        return count == null ? 0L : count;
    }

    @Override
    public long countPitcherRecords(UUID gameId) {
        Long count = jdbcTemplate.queryForObject(PITCHER_COUNT_SQL, Long.class, gameId);
        return count == null ? 0L : count;
    }

    private BatterRecordReadRow mapBatter(ResultSet rs) throws SQLException {
        return new BatterRecordReadRow(
                rs.getObject("team_id", UUID.class),
                rs.getInt("source_order"),
                getInteger(rs, "batting_order"),
                rs.getString("position"),
                rs.getString("player_name"),
                getInteger(rs, "at_bats"),
                getInteger(rs, "runs"),
                getInteger(rs, "hits"),
                getInteger(rs, "rbi"),
                getInteger(rs, "home_runs"),
                getInteger(rs, "walks"),
                getInteger(rs, "strikeouts"),
                getInteger(rs, "stolen_bases"),
                getInteger(rs, "grounded_into_double_play"),
                getInteger(rs, "errors"),
                rs.getString("batting_average"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private PitcherRecordReadRow mapPitcher(ResultSet rs) throws SQLException {
        return new PitcherRecordReadRow(
                rs.getObject("team_id", UUID.class),
                rs.getInt("source_order"),
                getInteger(rs, "pitching_order"),
                rs.getString("player_name"),
                rs.getString("appearance"),
                rs.getString("decision_result"),
                getInteger(rs, "wins"),
                getInteger(rs, "losses"),
                getInteger(rs, "saves"),
                rs.getString("innings_pitched"),
                getInteger(rs, "batters_faced"),
                getInteger(rs, "pitch_count"),
                getInteger(rs, "at_bats"),
                getInteger(rs, "hits"),
                getInteger(rs, "home_runs"),
                getInteger(rs, "walks_or_hit_by_pitch"),
                getInteger(rs, "strikeouts"),
                getInteger(rs, "runs"),
                getInteger(rs, "earned_runs"),
                rs.getString("era"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private Integer getInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
