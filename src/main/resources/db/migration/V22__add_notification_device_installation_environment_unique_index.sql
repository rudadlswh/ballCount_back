DO $$
DECLARE
    constraint_record record;
    index_record record;
BEGIN
    FOR constraint_record IN
        SELECT constraint_name
        FROM information_schema.constraint_column_usage
        WHERE table_schema = 'kbo_crawler_api'
          AND table_name = 'notification_devices'
          AND column_name = 'installation_id'
          AND constraint_name IN (
              SELECT tc.constraint_name
              FROM information_schema.table_constraints tc
              WHERE tc.table_schema = 'kbo_crawler_api'
                AND tc.table_name = 'notification_devices'
                AND tc.constraint_type = 'UNIQUE'
          )
    LOOP
        EXECUTE format('ALTER TABLE kbo_crawler_api.notification_devices DROP CONSTRAINT IF EXISTS %I', constraint_record.constraint_name);
    END LOOP;

    FOR index_record IN
        SELECT indexname
        FROM pg_indexes
        WHERE schemaname = 'kbo_crawler_api'
          AND tablename = 'notification_devices'
          AND indexdef ILIKE 'CREATE UNIQUE INDEX%'
          AND indexdef ILIKE '%(installation_id)%'
    LOOP
        EXECUTE format('DROP INDEX IF EXISTS kbo_crawler_api.%I', index_record.indexname);
    END LOOP;

    IF EXISTS (
        SELECT 1
        FROM kbo_crawler_api.notification_devices
        WHERE installation_id IS NOT NULL
        GROUP BY platform, environment, installation_id
        HAVING count(*) > 1
    ) THEN
        RAISE WARNING 'Skipping ux_notification_devices_platform_env_installation because duplicate notification_devices rows exist.';
    ELSE
        CREATE UNIQUE INDEX IF NOT EXISTS ux_notification_devices_platform_env_installation
            ON kbo_crawler_api.notification_devices (platform, environment, installation_id)
            WHERE installation_id IS NOT NULL;
    END IF;
END $$;
