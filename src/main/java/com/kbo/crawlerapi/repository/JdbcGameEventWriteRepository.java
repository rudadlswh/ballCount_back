package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGameEventWriteRepository implements GameEventWriteRepository {

    private static final String EVENT_UPSERT_SQL = """
            INSERT INTO game_events (
                id,
                game_id,
                provider_event_id,
                sequence_number,
                inning,
                inning_half,
                event_type,
                event_text,
                source_updated_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (game_id, sequence_number) DO UPDATE SET
                provider_event_id = excluded.provider_event_id,
                inning = excluded.inning,
                inning_half = excluded.inning_half,
                event_type = excluded.event_type,
                event_text = excluded.event_text,
                source_updated_at = excluded.source_updated_at,
                updated_at = now()
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcGameEventWriteRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int upsertEvents(List<GameEventWriteRow> rows) {
        int[][] results = jdbcTemplate.batchUpdate(EVENT_UPSERT_SQL, rows, 100, (ps, row) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, row.gameId());
            ps.setString(3, row.providerEventId());
            ps.setInt(4, row.sequenceNumber());
            ps.setObject(5, row.inning());
            ps.setString(6, row.inningHalf());
            ps.setString(7, row.eventType());
            ps.setString(8, row.eventText());
            ps.setObject(9, row.sourceUpdatedAt());
        });
        return count(results);
    }

    private int count(int[][] results) {
        int count = 0;
        for (int[] batch : results) {
            for (int result : batch) {
                count += Math.max(result, 0);
            }
        }
        return count;
    }
}
