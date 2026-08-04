package com.kbo.crawlerapi.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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

    public Optional<UUID> resolveGameId(String gameIdentifier) {
        return jdbcTemplate.query(
                """
                SELECT id
                FROM games
                WHERE id::text = ?
                   OR public_game_id = ?
                   OR provider_game_id = ?
                ORDER BY CASE
                    WHEN id::text = ? THEN 0
                    WHEN public_game_id = ? THEN 1
                    ELSE 2
                END
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                gameIdentifier,
                gameIdentifier,
                gameIdentifier,
                gameIdentifier,
                gameIdentifier
        ).stream().findFirst();
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

    public List<AttendanceGameRecordRow> findRecords(UUID installationId) {
        return jdbcTemplate.query(
                """
                SELECT
                    g.id AS game_id,
                    g.public_game_id,
                    g.game_date,
                    g.scheduled_at,
                    g.stadium,
                    g.status,
                    g.is_cancelled,
                    g.is_postponed,
                    away.team_code AS away_team_id,
                    away.name AS away_team_name,
                    away.short_name AS away_team_short_name,
                    away.logo_url AS away_team_logo_url,
                    home.team_code AS home_team_id,
                    home.name AS home_team_name,
                    home.short_name AS home_team_short_name,
                    home.logo_url AS home_team_logo_url,
                    g.away_score,
                    g.home_score
                FROM attendance_records attendance
                JOIN games g ON g.id = attendance.game_id
                JOIN teams away ON away.id = g.away_team_id
                JOIN teams home ON home.id = g.home_team_id
                WHERE attendance.installation_id = ?
                ORDER BY g.scheduled_at DESC NULLS LAST, g.game_date DESC, attendance.created_at DESC
                """,
                (rs, rowNum) -> new AttendanceGameRecordRow(
                        rs.getObject("game_id", UUID.class),
                        rs.getString("public_game_id"),
                        rs.getObject("game_date", LocalDate.class),
                        rs.getObject("scheduled_at", OffsetDateTime.class),
                        rs.getString("stadium"),
                        rs.getString("status"),
                        rs.getBoolean("is_cancelled"),
                        rs.getBoolean("is_postponed"),
                        rs.getString("away_team_id"),
                        rs.getString("away_team_name"),
                        rs.getString("away_team_short_name"),
                        rs.getString("away_team_logo_url"),
                        rs.getString("home_team_id"),
                        rs.getString("home_team_name"),
                        rs.getString("home_team_short_name"),
                        rs.getString("home_team_logo_url"),
                        (Integer) rs.getObject("away_score"),
                        (Integer) rs.getObject("home_score")
                ),
                installationId
        );
    }
}
