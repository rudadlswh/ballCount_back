-- V27.1 was introduced after higher versions had already reached some schemas.
-- Keep the original migration immutable and make the final schema contract
-- explicit for every active Flyway schema.
DO $$
DECLARE
    incompatible_columns TEXT;
BEGIN
    SELECT string_agg(column_info.column_name || ':' || column_info.data_type, ', ' ORDER BY column_info.column_name)
    INTO incompatible_columns
    FROM information_schema.columns column_info
    WHERE column_info.table_schema = '${appSchema}'
      AND column_info.table_name = 'notification_devices'
      AND column_info.column_name IN (
          'game_start_enabled',
          'score_change_enabled',
          'lead_change_enabled',
          'game_end_enabled',
          'on_base_enabled',
          'inning_change_enabled',
          'favorite_team_only_enabled',
          'mute_when_losing_enabled'
      )
      AND column_info.data_type <> 'boolean';

    IF incompatible_columns IS NOT NULL THEN
        RAISE EXCEPTION
            'notification delivery setting columns must be boolean in schema %: %',
            '${appSchema}',
            incompatible_columns;
    END IF;
END
$$;

ALTER TABLE ${appSchema}.notification_devices
    ADD COLUMN IF NOT EXISTS game_start_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS score_change_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS lead_change_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS game_end_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS on_base_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS inning_change_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS favorite_team_only_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS mute_when_losing_enabled BOOLEAN;

UPDATE ${appSchema}.notification_devices
SET game_start_enabled = COALESCE(game_start_enabled, TRUE),
    score_change_enabled = COALESCE(score_change_enabled, TRUE),
    lead_change_enabled = COALESCE(lead_change_enabled, TRUE),
    game_end_enabled = COALESCE(game_end_enabled, TRUE),
    on_base_enabled = COALESCE(on_base_enabled, FALSE),
    inning_change_enabled = COALESCE(inning_change_enabled, FALSE),
    favorite_team_only_enabled = COALESCE(favorite_team_only_enabled, FALSE),
    mute_when_losing_enabled = COALESCE(mute_when_losing_enabled, FALSE)
WHERE game_start_enabled IS NULL
   OR score_change_enabled IS NULL
   OR lead_change_enabled IS NULL
   OR game_end_enabled IS NULL
   OR on_base_enabled IS NULL
   OR inning_change_enabled IS NULL
   OR favorite_team_only_enabled IS NULL
   OR mute_when_losing_enabled IS NULL;

ALTER TABLE ${appSchema}.notification_devices
    ALTER COLUMN game_start_enabled SET DEFAULT TRUE,
    ALTER COLUMN game_start_enabled SET NOT NULL,
    ALTER COLUMN score_change_enabled SET DEFAULT TRUE,
    ALTER COLUMN score_change_enabled SET NOT NULL,
    ALTER COLUMN lead_change_enabled SET DEFAULT TRUE,
    ALTER COLUMN lead_change_enabled SET NOT NULL,
    ALTER COLUMN game_end_enabled SET DEFAULT TRUE,
    ALTER COLUMN game_end_enabled SET NOT NULL,
    ALTER COLUMN on_base_enabled SET DEFAULT FALSE,
    ALTER COLUMN on_base_enabled SET NOT NULL,
    ALTER COLUMN inning_change_enabled SET DEFAULT FALSE,
    ALTER COLUMN inning_change_enabled SET NOT NULL,
    ALTER COLUMN favorite_team_only_enabled SET DEFAULT FALSE,
    ALTER COLUMN favorite_team_only_enabled SET NOT NULL,
    ALTER COLUMN mute_when_losing_enabled SET DEFAULT FALSE,
    ALTER COLUMN mute_when_losing_enabled SET NOT NULL;

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
