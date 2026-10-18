package com.kbo.crawlerapi.service;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataRetentionService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int BATCH_SIZE = 1_000;
    private static final String TOKEN_GAME_MATCH = """
            (lower(t.database_id) = g.id::text
             OR lower(t.public_game_id) = lower(g.public_game_id)
             OR lower(t.provider_game_id) = lower(g.provider_game_id)
             OR lower(t.stable_detail_identity) = 'public:' || lower(g.public_game_id)
             OR lower(t.stable_detail_identity) = 'provider:' || lower(g.provider_game_id))
            """;

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final Clock clock;

    public DataRetentionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.clock = clock;
    }

    @Transactional(timeout = 20)
    public CleanupResult cleanup() {
        // Transaction-scoped lock also prevents two application instances cleaning the same schema.
        if (!Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(hashtext(current_schema()), hashtext('kbo-data-retention'))",
                Boolean.class))) return new CleanupResult(0, 0, 0, 0, 0, 0);

        var now = clock.instant().atZone(KST);
        var tokenDate = now.toLocalTime().isBefore(LocalTime.of(8, 0))
                ? now.toLocalDate().minusDays(1) : now.toLocalDate();
        var parameters = Map.<String, Object>of(
                "now", now.toOffsetDateTime(), "tokenDate", tokenDate,
                "week", now.minusWeeks(1).toOffsetDateTime(),
                "threeWeeks", now.minusWeeks(3).toOffsetDateTime(),
                "twoMonths", now.minusMonths(2).toOffsetDateTime(), "batch", BATCH_SIZE);

        int gameEvents = namedJdbc.update("""
                DELETE FROM game_events WHERE id IN (
                    SELECT e.id FROM game_events e JOIN games g ON g.id = e.game_id
                    WHERE g.status = 'final' AND g.final_confirmed_at <= :threeWeeks
                      AND e.updated_at <= :threeWeeks
                    ORDER BY e.updated_at, e.id LIMIT :batch FOR UPDATE OF e SKIP LOCKED
                )
                """, parameters);

        int activityTokens = namedJdbc.update("""
                DELETE FROM live_activity_tokens WHERE id IN (
                    SELECT t.id FROM live_activity_tokens t
                    WHERE NOT t.active
                      AND EXISTS (SELECT 1 FROM games g WHERE %s
                          AND g.status = 'final' AND g.final_confirmed_at <= :now AND g.game_date < :tokenDate)
                      AND NOT EXISTS (SELECT 1 FROM games g WHERE %s
                          AND (g.status <> 'final' OR g.final_confirmed_at IS NULL
                               OR g.final_confirmed_at > :now OR g.game_date >= :tokenDate))
                    ORDER BY t.last_seen_at, t.id LIMIT :batch FOR UPDATE OF t SKIP LOCKED
                )
                """.formatted(TOKEN_GAME_MATCH, TOKEN_GAME_MATCH), parameters);

        // Deleting a terminal outbox row also deletes its per-device deliveries through the FK.
        int outbox = namedJdbc.update("""
                DELETE FROM notification_outbox WHERE event_id IN (
                    SELECT event_id FROM notification_outbox
                    WHERE status IN ('done', 'expired') AND expires_at <= :week
                      AND (lease_until IS NULL OR lease_until <= :now)
                    ORDER BY expires_at, event_id LIMIT :batch FOR UPDATE SKIP LOCKED
                )
                """, parameters);

        // Keep only the deduplication key before removing notification contents, atomically.
        int notifications = namedJdbc.update("""
                WITH candidates AS MATERIALIZED (
                    SELECT e.id, e.event_key FROM notification_events e JOIN games g ON g.id = e.game_id
                    WHERE g.status = 'final' AND g.final_confirmed_at <= :week
                      AND e.created_at <= :week AND (e.sent_at IS NULL OR e.sent_at <= :week)
                      AND e.delivery_status IN ('sent', 'skipped', 'failed', 'partial_failed', 'expired')
                      AND NOT EXISTS (SELECT 1 FROM notification_outbox o WHERE o.event_id = e.id
                          AND (o.status NOT IN ('done', 'expired') OR o.expires_at > :week OR o.lease_until > :now))
                    ORDER BY e.created_at, e.id LIMIT :batch FOR UPDATE OF e SKIP LOCKED
                ), archived AS (
                    INSERT INTO notification_event_keys (event_key)
                    SELECT event_key FROM candidates ON CONFLICT DO NOTHING
                )
                DELETE FROM notification_events WHERE id IN (SELECT id FROM candidates)
                """, parameters);

        int failures = namedJdbc.update("""
                DELETE FROM crawl_failures WHERE id IN (
                    SELECT f.id FROM crawl_failures f WHERE f.occurred_at <= :twoMonths
                      AND (f.crawl_job_id IS NULL OR EXISTS (SELECT 1 FROM crawl_jobs j WHERE j.id = f.crawl_job_id
                          AND j.status IN ('succeeded', 'partial_success', 'failed')
                          AND j.finished_at <= :twoMonths AND j.updated_at <= :twoMonths))
                    ORDER BY f.occurred_at, f.id LIMIT :batch FOR UPDATE OF f SKIP LOCKED
                )
                """, parameters);
        int jobs = namedJdbc.update("""
                DELETE FROM crawl_jobs WHERE id IN (
                    SELECT j.id FROM crawl_jobs j WHERE j.status IN ('succeeded', 'partial_success', 'failed')
                      AND j.finished_at <= :twoMonths AND j.updated_at <= :twoMonths
                      AND NOT EXISTS (SELECT 1 FROM crawl_failures f WHERE f.crawl_job_id = j.id)
                    ORDER BY j.finished_at, j.id LIMIT :batch FOR UPDATE OF j SKIP LOCKED
                )
                """, parameters);
        return new CleanupResult(gameEvents, activityTokens, outbox, notifications, failures, jobs);
    }

    public record CleanupResult(int gameEvents, int activityTokens, int outbox,
                                int notifications, int failures, int jobs) {
        public int total() {
            return gameEvents + activityTokens + outbox + notifications + failures + jobs;
        }
    }
}
