-- V27.1, V28 and V32 used a production-schema-qualified table name. Repair the
-- active Flyway schema without editing migrations that may already be applied.
ALTER TABLE ${appSchema}.notification_devices
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

UPDATE ${appSchema}.notification_devices
SET notification_authorization_status = CASE
    WHEN notifications_enabled THEN 'authorized'
    ELSE 'denied'
END
WHERE notification_authorization_status IS NULL;

ALTER TABLE ${appSchema}.notification_devices
    ALTER COLUMN notification_authorization_status SET DEFAULT 'not_determined',
    ALTER COLUMN notification_authorization_status SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_info
        JOIN pg_namespace namespace_info ON namespace_info.oid = constraint_info.connamespace
        WHERE constraint_info.conname = 'ck_notification_devices_quiet_start_hour'
          AND namespace_info.nspname = '${appSchema}'
    ) THEN
        ALTER TABLE ${appSchema}.notification_devices
            ADD CONSTRAINT ck_notification_devices_quiet_start_hour
            CHECK (quiet_hours_start_hour BETWEEN 0 AND 23);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_info
        JOIN pg_namespace namespace_info ON namespace_info.oid = constraint_info.connamespace
        WHERE constraint_info.conname = 'ck_notification_devices_quiet_end_hour'
          AND namespace_info.nspname = '${appSchema}'
    ) THEN
        ALTER TABLE ${appSchema}.notification_devices
            ADD CONSTRAINT ck_notification_devices_quiet_end_hour
            CHECK (quiet_hours_end_hour BETWEEN 0 AND 23);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_notification_devices_delivery_settings
    ON ${appSchema}.notification_devices (
        environment,
        platform,
        notifications_enabled,
        favorite_team_id,
        favorite_team_only_enabled,
        score_change_enabled,
        lead_change_enabled,
        game_start_enabled,
        game_end_enabled,
        on_base_enabled,
        inning_change_enabled
    );
