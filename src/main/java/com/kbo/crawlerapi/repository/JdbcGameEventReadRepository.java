package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGameEventReadRepository implements GameEventReadRepository {

    private static final String RECENT_EVENTS_SQL = """
            SELECT
                sequence_number,
                inning,
                inning_half,
                event_type,
                event_text
            FROM kbo_crawler_api.game_events
            WHERE game_id = ?
            ORDER BY sequence_number DESC
            LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcGameEventReadRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<GameEventRow> findRecentByGameId(UUID gameId, int limit) {
        if (gameId == null || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query(
                RECENT_EVENTS_SQL,
                (rs, rowNum) -> new GameEventRow(
                        rs.getInt("sequence_number"),
                        (Integer) rs.getObject("inning"),
                        rs.getString("inning_half"),
                        rs.getString("event_type"),
                        rs.getString("event_text")
                ),
                gameId,
                limit
        );
    }
}
