-- Read-only diagnostics for the V27.1 history gap.
-- Run with psql against the same Supabase database used by the backend.
BEGIN TRANSACTION READ ONLY;

-- Production Flyway history: version, order, checksum, and success state.
SELECT
    installed_rank,
    version,
    description,
    type,
    script,
    checksum,
    installed_on,
    success
FROM kbo_crawler_api.flyway_schema_history
ORDER BY installed_rank;

-- Development history in the currently accessible Supabase database.
SELECT
    installed_rank,
    version,
    description,
    type,
    script,
    checksum,
    installed_on,
    success
FROM kbo_crawler_api_dev.flyway_schema_history
ORDER BY installed_rank;

-- Versions present in code but absent from production history.
WITH expected_versions(version) AS (
    VALUES
        ('1'), ('2'), ('3'), ('4'), ('5'), ('6'), ('7'), ('8'), ('9'), ('10'),
        ('11'), ('12'), ('13'), ('14'), ('15'), ('16'), ('17'), ('18'), ('19'), ('20'),
        ('21'), ('22'), ('23'), ('24'), ('25'), ('26'), ('27'), ('27.1'), ('28'), ('29'),
        ('30'), ('31'), ('32'), ('33'), ('34'), ('35'), ('36')
)
SELECT expected.version AS missing_version
FROM expected_versions expected
WHERE NOT EXISTS (
    SELECT 1
    FROM kbo_crawler_api.flyway_schema_history history
    WHERE history.version = expected.version
      AND history.success
)
ORDER BY string_to_array(expected.version, '.')::INTEGER[];

-- Exact V27.1 column contract in production and development.
WITH expected_columns(column_name, expected_type, expected_default, expected_nullable) AS (
    VALUES
        ('game_start_enabled', 'boolean', 'true', 'NO'),
        ('score_change_enabled', 'boolean', 'true', 'NO'),
        ('lead_change_enabled', 'boolean', 'true', 'NO'),
        ('game_end_enabled', 'boolean', 'true', 'NO'),
        ('on_base_enabled', 'boolean', 'false', 'NO'),
        ('inning_change_enabled', 'boolean', 'false', 'NO'),
        ('favorite_team_only_enabled', 'boolean', 'false', 'NO'),
        ('mute_when_losing_enabled', 'boolean', 'false', 'NO')
)
SELECT
    schema_info.schema_name AS table_schema,
    expected.column_name,
    expected.expected_type,
    column_info.data_type AS actual_type,
    expected.expected_default,
    column_info.column_default AS actual_default,
    expected.expected_nullable,
    column_info.is_nullable AS actual_nullable
FROM (VALUES ('kbo_crawler_api'), ('kbo_crawler_api_dev')) schema_info(schema_name)
CROSS JOIN expected_columns expected
LEFT JOIN information_schema.columns column_info
    ON column_info.table_schema = schema_info.schema_name
   AND column_info.table_name = 'notification_devices'
   AND column_info.column_name = expected.column_name
ORDER BY schema_info.schema_name, expected.column_name;

-- Table constraints, including CHECK, UNIQUE, and primary-key definitions.
SELECT
    namespace_info.nspname AS table_schema,
    constraint_info.conname AS constraint_name,
    constraint_info.contype AS constraint_type,
    pg_get_constraintdef(constraint_info.oid, TRUE) AS definition
FROM pg_constraint constraint_info
JOIN pg_class table_info ON table_info.oid = constraint_info.conrelid
JOIN pg_namespace namespace_info ON namespace_info.oid = table_info.relnamespace
WHERE table_info.relname = 'notification_devices'
  AND namespace_info.nspname IN ('kbo_crawler_api', 'kbo_crawler_api_dev')
ORDER BY table_schema, constraint_name;

-- All indexes and predicates on the target table.
SELECT
    schemaname,
    indexname,
    indexdef
FROM pg_indexes
WHERE tablename = 'notification_devices'
  AND schemaname IN ('kbo_crawler_api', 'kbo_crawler_api_dev')
ORDER BY schemaname, indexname;

-- Existing-row backfill verification without assuming that every JSON key exists.
SELECT
    'production' AS environment,
    COUNT(*) AS total_rows,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'game_start_enabled' IS NULL) AS game_start_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'score_change_enabled' IS NULL) AS score_change_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'lead_change_enabled' IS NULL) AS lead_change_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'game_end_enabled' IS NULL) AS game_end_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'on_base_enabled' IS NULL) AS on_base_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'inning_change_enabled' IS NULL) AS inning_change_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'favorite_team_only_enabled' IS NULL) AS favorite_only_missing_or_null,
    COUNT(*) FILTER (WHERE to_jsonb(device)->'mute_when_losing_enabled' IS NULL) AS mute_losing_missing_or_null
FROM kbo_crawler_api.notification_devices device
UNION ALL
SELECT
    'development',
    COUNT(*),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'game_start_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'score_change_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'lead_change_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'game_end_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'on_base_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'inning_change_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'favorite_team_only_enabled' IS NULL),
    COUNT(*) FILTER (WHERE to_jsonb(device)->'mute_when_losing_enabled' IS NULL)
FROM kbo_crawler_api_dev.notification_devices device;

ROLLBACK;
