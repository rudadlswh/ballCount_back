package com.kbo.crawlerapi.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AttendanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AttendanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean gameExists(UUID gameId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM games WHERE id = ?)",
                Boolean.class,
                gameId
        );
        return Boolean.TRUE.equals(exists);
    }

    public void upsert(UUID installationId, UUID gameId) {
        jdbcTemplate.update("""
                INSERT INTO attendance_records (installation_id, game_id, source)
                VALUES (?, ?, 'manual')
                ON CONFLICT (installation_id, game_id)
                DO UPDATE SET
                    source = 'manual',
                    updated_at = now()
                """, installationId, gameId);
    }

    public void delete(UUID installationId, UUID gameId) {
        jdbcTemplate.update(
                "DELETE FROM attendance_records WHERE installation_id = ? AND game_id = ?",
                installationId,
                gameId
        );
    }

    public List<UUID> findGameIds(UUID installationId) {
        return jdbcTemplate.query(
                """
                SELECT game_id
                FROM attendance_records
                WHERE installation_id = ?
                ORDER BY created_at ASC, game_id ASC
                """,
                (rs, rowNum) -> rs.getObject("game_id", UUID.class),
                installationId
        );
    }
}
