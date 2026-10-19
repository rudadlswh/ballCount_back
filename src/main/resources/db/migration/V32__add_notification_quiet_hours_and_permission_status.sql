ALTER TABLE kbo_crawler_api.notification_devices
    ADD COLUMN IF NOT EXISTS game_start_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS score_change_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS lead_change_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS game_end_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS on_base_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS inning_change_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS favorite_team_only_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS mute_when_losing_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS rain_delay_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS quiet_hours_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS quiet_hours_start_hour SMALLINT NOT NULL DEFAULT 23,
    ADD COLUMN IF NOT EXISTS quiet_hours_end_hour SMALLINT NOT NULL DEFAULT 7,
    ADD COLUMN IF NOT EXISTS notification_authorization_status VARCHAR(30);

UPDATE kbo_crawler_api.notification_devices
SET notification_authorization_status = CASE
    WHEN notifications_enabled THEN 'authorized'
    ELSE 'denied'
END
WHERE notification_authorization_status IS NULL;

ALTER TABLE kbo_crawler_api.notification_devices
    ALTER COLUMN notification_authorization_status SET DEFAULT 'not_determined',
    ALTER COLUMN notification_authorization_status SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_notification_devices_quiet_start_hour'
    ) THEN
        ALTER TABLE kbo_crawler_api.notification_devices
            ADD CONSTRAINT ck_notification_devices_quiet_start_hour
            CHECK (quiet_hours_start_hour BETWEEN 0 AND 23);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_notification_devices_quiet_end_hour'
    ) THEN
        ALTER TABLE kbo_crawler_api.notification_devices
            ADD CONSTRAINT ck_notification_devices_quiet_end_hour
            CHECK (quiet_hours_end_hour BETWEEN 0 AND 23);
    END IF;
END
$$;
