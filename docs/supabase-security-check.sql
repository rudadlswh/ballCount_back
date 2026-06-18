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

-- 3) Tables with RLS disabled. In exposed schemas, every table should have RLS
-- enabled unless there is a documented reason not to expose it through the Data API.
select
    schemaname,
    tablename,
    rowsecurity as rls_enabled,
    forcerowsecurity as force_rls
from pg_tables
where schemaname = 'kbo_crawler_api'
  and rowsecurity = false
order by tablename;

-- 4) Sensitive backend-owned tables: remove anon/authenticated access.
-- These tables should be written/read only by the backend service credentials.
revoke all privileges on table kbo_crawler_api.notification_devices from anon, authenticated;
revoke all privileges on table kbo_crawler_api.live_activity_tokens from anon, authenticated;
revoke all privileges on table kbo_crawler_api.crawl_jobs from anon, authenticated;
revoke all privileges on table kbo_crawler_api.crawl_failures from anon, authenticated;

alter table kbo_crawler_api.notification_devices enable row level security;
alter table kbo_crawler_api.live_activity_tokens enable row level security;
alter table kbo_crawler_api.crawl_jobs enable row level security;
alter table kbo_crawler_api.crawl_failures enable row level security;

-- 5) Public app read examples. Keep grants narrow: grant only read projections
-- that the iOS app must call directly, not backend-owned raw tables.
grant usage on schema kbo_crawler_api to anon, authenticated;

grant select on table kbo_crawler_api.teams to anon, authenticated;
grant select on table kbo_crawler_api.games to anon, authenticated;
grant select on table kbo_crawler_api.team_rank_2026 to anon, authenticated;
grant select on table kbo_crawler_api.public_latest_game_snapshots to anon, authenticated;
grant select on table kbo_crawler_api.public_game_batter_records to anon, authenticated;
grant select on table kbo_crawler_api.public_game_pitcher_records to anon, authenticated;

revoke insert, update, delete on table kbo_crawler_api.teams from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.games from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.team_rank_2026 from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_latest_game_snapshots from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_game_batter_records from anon, authenticated;
revoke insert, update, delete on table kbo_crawler_api.public_game_pitcher_records from anon, authenticated;

-- 6) View safety check. On Postgres 15+, public views should use
-- security_invoker=true so underlying table RLS still applies.
select
    n.nspname as schema_name,
    c.relname as view_name,
    c.reloptions
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'kbo_crawler_api'
  and c.relkind in ('v', 'm')
order by c.relname;
