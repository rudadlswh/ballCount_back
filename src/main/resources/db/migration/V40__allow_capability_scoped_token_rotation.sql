-- A public activity ID cannot authorize replacing a different APNs token.
ALTER TABLE ${appSchema}.live_activity_tokens
    DROP CONSTRAINT IF EXISTS uk_live_activity_tokens_activity_id;

CREATE INDEX IF NOT EXISTS idx_live_activity_tokens_activity_id
    ON ${appSchema}.live_activity_tokens (activity_id);

-- Installation IDs cannot authorize overwriting an existing device token.
DROP INDEX IF EXISTS ${appSchema}.ux_notification_devices_platform_env_installation;
CREATE INDEX IF NOT EXISTS idx_notification_devices_platform_env_installation
    ON ${appSchema}.notification_devices (platform, environment, installation_id);

ALTER TABLE ${appSchema}.live_activity_push_to_start_tokens
    DROP CONSTRAINT IF EXISTS uk_live_activity_pts_platform_env_installation;
CREATE INDEX IF NOT EXISTS idx_live_activity_pts_platform_env_installation
    ON ${appSchema}.live_activity_push_to_start_tokens (platform, environment, installation_id);
