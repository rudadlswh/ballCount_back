-- Supabase public-read verification and remediation for iOS team and game queries.
-- Keep writes restricted to trusted backend/admin paths.

notify pgrst, 'reload schema';

select table_schema, table_name
from information_schema.tables
where table_schema = 'public'
  and table_name in ('teams', 'games')
order by table_name;

select schemaname, tablename, rowsecurity
from pg_tables
where schemaname = 'public'
  and tablename in ('teams', 'games')
order by tablename;

select has_schema_privilege('anon', 'public', 'USAGE') as anon_has_public_usage;

select table_name, has_table_privilege('anon', format('public.%I', table_name), 'SELECT') as anon_has_select
from (values ('teams'), ('games')) as required_tables(table_name)
order by table_name;

select schemaname, tablename, policyname, roles, cmd, qual
from pg_policies
where schemaname = 'public'
  and tablename in ('teams', 'games')
order by tablename, policyname;

alter table public.teams enable row level security;
alter table public.games enable row level security;

grant usage on schema public to anon;
revoke all on table public.teams from anon;
revoke all on table public.games from anon;
grant select on table public.teams to anon;
grant select on table public.games to anon;

do $$
begin
  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'teams'
      and policyname = 'Allow public read teams'
  ) then
    create policy "Allow public read teams"
      on public.teams
      for select
      to anon
      using (true);
  end if;

  if not exists (
    select 1
    from pg_policies
    where schemaname = 'public'
      and tablename = 'games'
      and policyname = 'Allow public read games'
  ) then
    create policy "Allow public read games"
      on public.games
      for select
      to anon
      using (true);
  end if;
end $$;

select pg_notification_queue_usage() as notification_queue_usage;
notify pgrst, 'reload schema';
