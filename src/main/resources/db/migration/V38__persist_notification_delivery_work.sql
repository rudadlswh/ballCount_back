CREATE TABLE notification_outbox (
    event_id UUID PRIMARY KEY REFERENCES notification_events(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    attempts INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ,
    lease_token UUID
);
CREATE INDEX idx_notification_outbox_pending ON notification_outbox (available_at)
    WHERE status IN ('pending', 'processing');

CREATE TABLE notification_deliveries (
    event_id UUID NOT NULL REFERENCES notification_outbox(event_id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES notification_devices(id) ON DELETE CASCADE,
    platform VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'pending',
    error_message TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, device_id)
);
ALTER TABLE notification_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_deliveries ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON notification_outbox, notification_deliveries FROM PUBLIC, anon, authenticated;
