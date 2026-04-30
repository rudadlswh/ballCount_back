ALTER TABLE notification_devices
    ADD COLUMN IF NOT EXISTS environment VARCHAR(30) NOT NULL DEFAULT 'sandbox';

ALTER TABLE notification_devices
    DROP CONSTRAINT IF EXISTS uk_notification_devices_platform_token;

ALTER TABLE notification_devices
    ADD CONSTRAINT uk_notification_devices_platform_environment_token UNIQUE (platform, environment, device_token);

CREATE INDEX IF NOT EXISTS idx_notification_devices_platform_environment_enabled
    ON notification_devices (platform, environment, notifications_enabled);
