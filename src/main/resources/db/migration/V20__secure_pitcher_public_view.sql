DO $$
BEGIN
    IF to_regclass('kbo_crawler_api.public_game_pitcher_records') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.public_game_pitcher_records SET (security_invoker = true)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_game_pitcher_records IS ''Public app read projection. Uses security_invoker so underlying table RLS remains effective for anon/authenticated callers.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_game_pitcher_records TO anon, authenticated';
    END IF;
END $$;
