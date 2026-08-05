ALTER TABLE notification_devices
    ADD COLUMN IF NOT EXISTS monitored_game_id VARCHAR(200);

CREATE INDEX IF NOT EXISTS notification_devices_android_monitored_game_idx
    ON notification_devices (environment, monitored_game_id)
    WHERE platform = 'android' AND notifications_enabled = true AND monitored_game_id IS NOT NULL;
