-- KBO crawler API Supabase pre-production security checks.
-- Target schema: kbo_crawler_api
-- Run in the Supabase SQL Editor with an owner/admin role. Review output before
-- running the REVOKE/GRANT examples below.

-- 1) anon/authenticated schema privileges.
select
    role_name,
    has_schema_privilege(role_name, 'kbo_crawler_api', 'USAGE') as has_schema_usage,
    has_schema_privilege(role_name, 'kbo_crawler_api', 'CREATE') as has_schema_create
from (values ('anon'), ('authenticated')) as roles(role_name);

-- 2) anon/authenticated table and view privileges in kbo_crawler_api.
select
    n.nspname as schema_name,
    c.relname as object_name,
    c.relkind as object_kind,
    role_name,
    has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'SELECT') as can_select,
    has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'INSERT') as can_insert,
    has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'UPDATE') as can_update,
    has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'DELETE') as can_delete
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
cross join (values ('anon'), ('authenticated')) as roles(role_name)
where n.nspname = 'kbo_crawler_api'
  and c.relkind in ('r', 'p', 'v', 'm')
order by c.relname, role_name;

-- 3) Tables with RLS disabled. Keep RLS enabled on all app-owned tables.
select
    schemaname,
    tablename,
    rowsecurity as rls_enabled,
    forcerowsecurity as force_rls
from pg_tables
where schemaname = 'kbo_crawler_api'
  and rowsecurity = false
order by tablename;

-- 4) Sensitive/backend-owned and raw tables: remove anon/authenticated access.
-- The iOS app should read narrow public projection views instead of raw tables
-- that may contain device identifiers, raw hashes, crawler failures, or internal
-- operational state.
revoke all privileges on table kbo_crawler_api.game_snapshots from anon, authenticated;
revoke all privileges on table kbo_crawler_api.line_scores from anon, authenticated;
revoke all privileges on table kbo_crawler_api.game_events from anon, authenticated;
revoke all privileges on table kbo_crawler_api.game_batter_records from anon, authenticated;
revoke all privileges on table kbo_crawler_api.game_pitcher_records from anon, authenticated;
revoke all privileges on table kbo_crawler_api.team_ranks from anon, authenticated;
revoke all privileges on table kbo_crawler_api.notification_devices from anon, authenticated;
revoke all privileges on table kbo_crawler_api.notification_events from anon, authenticated;
revoke all privileges on table kbo_crawler_api.live_activity_tokens from anon, authenticated;
revoke all privileges on table kbo_crawler_api.live_activity_push_to_start_tokens from anon, authenticated;
revoke all privileges on table kbo_crawler_api.crawl_jobs from anon, authenticated;
revoke all privileges on table kbo_crawler_api.crawl_failures from anon, authenticated;
revoke all privileges on table kbo_crawler_api.crawl_raw_archive from anon, authenticated;

alter table kbo_crawler_api.teams enable row level security;
alter table kbo_crawler_api.games enable row level security;
alter table kbo_crawler_api.game_snapshots enable row level security;
alter table kbo_crawler_api.line_scores enable row level security;
alter table kbo_crawler_api.game_events enable row level security;
alter table kbo_crawler_api.game_batter_records enable row level security;
alter table kbo_crawler_api.game_pitcher_records enable row level security;
alter table kbo_crawler_api.team_ranks enable row level security;
alter table kbo_crawler_api.notification_devices enable row level security;
alter table kbo_crawler_api.notification_events enable row level security;
alter table kbo_crawler_api.live_activity_tokens enable row level security;
alter table kbo_crawler_api.live_activity_push_to_start_tokens enable row level security;
alter table kbo_crawler_api.crawl_jobs enable row level security;
alter table kbo_crawler_api.crawl_failures enable row level security;
alter table kbo_crawler_api.crawl_raw_archive enable row level security;

-- 5) Public app read examples. Keep grants narrow: anon/authenticated should
-- read app-facing projection views, not raw backend-owned tables.
grant usage on schema kbo_crawler_api to anon, authenticated;

grant select on table kbo_crawler_api.public_teams to anon, authenticated;
grant select on table kbo_crawler_api.public_games to anon, authenticated;
grant select on table kbo_crawler_api.public_game_events to anon, authenticated;
grant select on table kbo_crawler_api.team_rank_2026 to anon, authenticated;
grant select on table kbo_crawler_api.public_latest_game_snapshots to anon, authenticated;
grant select on table kbo_crawler_api.public_game_batter_records to anon, authenticated;
grant select on table kbo_crawler_api.public_game_pitcher_records to anon, authenticated;

revoke all privileges on table kbo_crawler_api.teams from anon, authenticated;
revoke all privileges on table kbo_crawler_api.games from anon, authenticated;
revoke all privileges on table kbo_crawler_api.game_events from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_teams from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_games from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_game_events from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.team_rank_2026 from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_latest_game_snapshots from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_game_batter_records from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_game_pitcher_records from anon, authenticated;

-- 6) View safety check. Current production policy uses security_invoker=false
-- for public projection views and treats each view definition as the public
-- boundary. Verify these views do not include sensitive columns.
--
-- Expected public projections:
-- public_teams: id, team_code, name, short_name
-- public_games: id, public_game_id, provider, provider_game_id,
--   official_provider_game_id, game_date, scheduled_at, stadium, status,
--   status_reason, home_team_id, away_team_id, home_score, away_score,
--   inning_state, is_cancelled, is_postponed, source_updated_at, updated_at,
--   final_confirmed_at, live_last_checked_at, away_starting_pitcher_name,
--   home_starting_pitcher_name
-- public_game_events: id, game_id, provider_event_id, sequence_number,
--   inning, inning_half, event_type, event_text
-- public_latest_game_snapshots/public_game_batter_records/
--   public_game_pitcher_records/team_rank_2026: app display fields only.
select
    n.nspname as schema_name,
    c.relname as view_name,
    c.reloptions
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'kbo_crawler_api'
  and c.relkind in ('v', 'm')
order by c.relname;

-- 7) Sensitive direct table grants must remain false for anon/authenticated.
select
    object_name,
    role_name,
    can_select,
    can_insert,
    can_update,
    can_delete
from (
    select
        c.relname as object_name,
        role_name,
        has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'SELECT') as can_select,
        has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'INSERT') as can_insert,
        has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'UPDATE') as can_update,
        has_table_privilege(role_name, format('%I.%I', n.nspname, c.relname), 'DELETE') as can_delete
    from pg_class c
    join pg_namespace n on n.oid = c.relnamespace
    cross join (values ('anon'), ('authenticated')) as roles(role_name)
    where n.nspname = 'kbo_crawler_api'
      and c.relname in (
          'teams',
          'games',
          'game_snapshots',
          'line_scores',
          'game_events',
          'game_batter_records',
          'game_pitcher_records',
          'team_ranks',
          'notification_devices',
          'notification_events',
          'live_activity_tokens',
          'live_activity_push_to_start_tokens',
          'crawl_jobs',
          'crawl_failures',
          'crawl_raw_archive',
          'flyway_schema_history'
      )
) grants
where can_select or can_insert or can_update or can_delete
order by object_name, role_name;

-- 8) Expected public projection view options. reloptions should show
-- security_invoker=false where the option is present.
alter view if exists kbo_crawler_api.public_latest_game_snapshots set (security_invoker = false);
alter view if exists kbo_crawler_api.public_game_batter_records set (security_invoker = false);
alter view if exists kbo_crawler_api.public_game_pitcher_records set (security_invoker = false);
alter view if exists kbo_crawler_api.team_rank_2026 set (security_invoker = false);
alter view if exists kbo_crawler_api.public_teams set (security_invoker = false);
alter view if exists kbo_crawler_api.public_games set (security_invoker = false);
alter view if exists kbo_crawler_api.public_game_events set (security_invoker = false);

-- TODO: evaluate switching public projection views to security_invoker=true
-- with explicit RLS policies after the app-facing RLS model is covered by
-- automated tests.
