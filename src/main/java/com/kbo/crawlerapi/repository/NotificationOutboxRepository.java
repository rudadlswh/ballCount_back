package com.kbo.crawlerapi.repository;

import com.kbo.crawlerapi.domain.NotificationDevice;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional
public class NotificationOutboxRepository {
    private final JdbcTemplate jdbc;

    public NotificationOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void enqueue(UUID eventId, List<NotificationDevice> devices, OffsetDateTime now) {
        jdbc.update("INSERT INTO notification_outbox(event_id, available_at, expires_at) VALUES (?, ?, ?)", eventId, now, now.plusMinutes(5));
        jdbc.batchUpdate("INSERT INTO notification_deliveries(event_id, device_id, platform) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
                devices.stream().map(device -> new Object[]{eventId, device.getId(), device.getPlatform()}).toList());
    }

    public UUID claim(UUID eventId, OffsetDateTime now) {
        UUID token = UUID.randomUUID();
        var rows = jdbc.queryForList("""
                UPDATE notification_outbox SET status='processing', attempts=attempts+1, lease_token=?, lease_until=?
                WHERE event_id=? AND expires_at>? AND attempts<5 AND available_at<=?
                  AND (status='pending' OR (status='processing' AND lease_until<=?))
                RETURNING event_id
                """, token, now.plusSeconds(60), eventId, now, now, now);
        if (rows.isEmpty()) return null;
        jdbc.update("UPDATE notification_deliveries SET status='pending' WHERE event_id=? AND status='failed'", eventId);
        return token;
    }

    public List<UUID> due(OffsetDateTime now) {
        var expired = jdbc.queryForList("UPDATE notification_outbox SET status='expired', lease_token=NULL WHERE expires_at<=? AND status IN ('pending','processing') RETURNING event_id", UUID.class, now);
        var expiredIds = expired.stream().map(id -> new Object[]{id}).toList();
        jdbc.batchUpdate("UPDATE notification_events SET delivery_status='expired' WHERE id=?", expiredIds);
        jdbc.batchUpdate("UPDATE notification_deliveries SET status='expired' WHERE event_id=? AND status IN ('pending','failed')", expiredIds);
        return jdbc.queryForList("""
                SELECT event_id FROM notification_outbox WHERE expires_at>? AND attempts<5 AND available_at<=?
                  AND (status='pending' OR (status='processing' AND lease_until<=?))
                ORDER BY available_at LIMIT 20
                """, UUID.class, now, now, now);
    }

    public List<UUID> targets(UUID eventId, String platform) {
        return jdbc.queryForList("SELECT device_id FROM notification_deliveries WHERE event_id=? AND platform=?", UUID.class, eventId, platform);
    }

    /** Returns a terminal outcome, or pending when this lease may send to this device. */
    public String beforeSend(UUID eventId, UUID token, UUID deviceId, OffsetDateTime now) {
        if (jdbc.update("UPDATE notification_outbox SET lease_until=? WHERE event_id=? AND lease_token=? AND status='processing' AND expires_at>?",
                now.plusSeconds(60), eventId, token, now) != 1) throw new IllegalStateException("Notification delivery lease lost");
        var status = jdbc.queryForList("SELECT status FROM notification_deliveries WHERE event_id=? AND device_id=?", String.class, eventId, deviceId);
        return status.isEmpty() ? "skipped" : status.get(0);
    }

    public void record(UUID eventId, UUID token, UUID deviceId, String status, String reason, OffsetDateTime now) {
        int updated = jdbc.update("""
                UPDATE notification_deliveries SET status=?, error_message=?, updated_at=?
                WHERE event_id=? AND device_id=? AND EXISTS (
                    SELECT 1 FROM notification_outbox WHERE event_id=? AND lease_token=? AND status='processing')
                """, status, reason, now, eventId, deviceId, eventId, token);
        if (updated != 1) throw new IllegalStateException("Notification delivery lease lost");
    }

    public void finish(UUID eventId, UUID token, boolean apnsReady, boolean fcmReady, OffsetDateTime now, String error) {
        // A provider outage is retryable. Changed preferences and removed devices are terminal skips.
        jdbc.update("""
                UPDATE notification_deliveries d SET status=CASE
                    WHEN CAST(? AS TEXT) IS NOT NULL OR (platform='ios' AND NOT ?) OR (platform='android' AND NOT ?) THEN 'failed' ELSE 'skipped' END,
                    error_message=COALESCE(?, 'not eligible'), updated_at=?
                WHERE event_id=? AND status='pending' AND EXISTS (
                    SELECT 1 FROM notification_outbox WHERE event_id=? AND lease_token=? AND status='processing')
                """, error, apnsReady, fcmReady, error, now, eventId, eventId, token);
        var totals = jdbc.queryForMap("SELECT count(*) FILTER (WHERE status='sent') AS sent, count(*) FILTER (WHERE status='failed') AS failed, max(error_message) FILTER (WHERE status='failed') AS failure FROM notification_deliveries WHERE event_id=?", eventId);
        boolean sent = ((Number) totals.get("sent")).intValue() > 0;
        boolean failed = ((Number) totals.get("failed")).intValue() > 0;
        int changed = jdbc.update("""
                UPDATE notification_outbox SET status=CASE WHEN ? AND attempts<5 THEN 'pending' ELSE 'done' END,
                    available_at=?, lease_until=NULL, lease_token=NULL
                WHERE event_id=? AND lease_token=? AND status='processing'
                """, failed, now.plusSeconds(30), eventId, token);
        if (changed == 1) jdbc.update("UPDATE notification_events SET delivery_status=?, error_message=? WHERE id=?",
                failed ? (sent ? "partial_failed" : "failed") : (sent ? "sent" : "skipped"),
                failed ? (error != null ? error : totals.get("failure")) : null, eventId);
    }
}
