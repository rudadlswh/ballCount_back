DO $$
BEGIN
    IF to_regclass('kbo_crawler_api.public_game_pitcher_records') IS NULL THEN
        EXECUTE $view$
            CREATE VIEW kbo_crawler_api.public_game_pitcher_records
            WITH (security_invoker = true) AS
            SELECT
                id,
                game_id,
                team_id,
                source_order,
                pitching_order,
                player_name,
                appearance,
                decision_result,
                wins,
                losses,
                saves,
                innings_pitched,
                batters_faced,
                pitch_count,
                at_bats,
                hits,
                home_runs,
                walks_or_hit_by_pitch,
                strikeouts,
                runs,
                earned_runs,
                era
            FROM kbo_crawler_api.game_pitcher_records
        $view$;
    END IF;

    IF to_regclass('kbo_crawler_api.public_latest_game_snapshots') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.public_latest_game_snapshots SET (security_invoker = true)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_latest_game_snapshots IS ''Public app read projection. Uses security_invoker so underlying table RLS remains effective for anon/authenticated callers.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_latest_game_snapshots TO anon, authenticated';
    END IF;

    IF to_regclass('kbo_crawler_api.public_game_batter_records') IS NOT NULL THEN
        EXECUTE 'ALTER VIEW kbo_crawler_api.public_game_batter_records SET (security_invoker = true)';
        EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_game_batter_records IS ''Public app read projection. Uses security_invoker so underlying table RLS remains effective for anon/authenticated callers.''';
        EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_game_batter_records TO anon, authenticated';
    END IF;

    EXECUTE 'COMMENT ON VIEW kbo_crawler_api.public_game_pitcher_records IS ''Public app read projection. Uses security_invoker so underlying table RLS remains effective for anon/authenticated callers.''';
    EXECUTE 'GRANT SELECT ON kbo_crawler_api.public_game_pitcher_records TO anon, authenticated';
END $$;
