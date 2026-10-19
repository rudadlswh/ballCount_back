CREATE INDEX IF NOT EXISTS idx_notification_devices_delivery_settings
    ON kbo_crawler_api.notification_devices (
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
