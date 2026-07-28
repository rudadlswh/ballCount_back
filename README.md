# KBO Crawler API

## Local setup

1. Create a repository-root `.env` file.
2. Set at least these keys in `.env`:
   - `SPRING_PROFILES_ACTIVE=local`
   - `APP_DB_SCHEMA=kbo_crawler_api_dev`
   - `APP_RUNTIME_ROLE=reader`
   - `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<database>?currentSchema=kbo_crawler_api_dev`
   - `SPRING_DATASOURCE_USERNAME`
   - `SPRING_DATASOURCE_PASSWORD`
3. Optionally override:
   - `SPRING_PROFILES_ACTIVE`

## Admin console

The server provides a Thymeleaf + HTMX operations console at `http://localhost:8088/admin`.
Set the following values outside Git before using it:

- `ADMIN_USERNAME` (defaults to `admin`)
- `ADMIN_PASSWORD` (required; there is no default password)
- `ADMIN_SESSION_TIMEOUT` (defaults to `30m`)
- `ADMIN_SESSION_COOKIE_SECURE` (`true` behind production HTTPS, `false` for local HTTP)
- `ADMIN_STALE_GAME_THRESHOLD` (defaults to `2m`)
- `ADMIN_LOG_CAPACITY` (defaults to `2000`, minimum effective capacity `100`)

The console masks configured usernames/passwords and never renders database URLs or secret values.
Browser sessions are accepted only by the read-only console routes. Existing mutating `/admin/crawl`,
`/admin/ranks`, and `/admin/test` APIs continue to require `X-Admin-Key` or `X-Admin-Api-Key`.
Application logs are retained in memory from process startup and the log screen returns at most 500 rows per search.

The application loads `.env` automatically through Spring Boot config import when you run from the repository root.
Local, development, and test profiles use the dedicated `kbo_crawler_api_dev` schema. Production uses `kbo_crawler_api`.
Startup fails when a non-production profile is configured with the production schema, or when local/development/test is configured with anything other than `kbo_crawler_api_dev`.
`APP_RUNTIME_ROLE` defaults to reader behavior. Reader or unset local runs must keep `APP_SYNC_ENABLED=false`.

## Run locally

```bash
./gradlew build
./gradlew bootRun
```

## Production profile

Use `SPRING_PROFILES_ACTIVE=production` for App Store production runtime. Start from
`.env.production.example`, then set the real database and APNs values outside Git:

- `APP_DB_SCHEMA=kbo_crawler_api`
- `SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/<database>?currentSchema=kbo_crawler_api`
- `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `APNS_ENV=production`
- `APNS_TEAM_ID`, `APNS_KEY_ID`, `APNS_BUNDLE_ID=com.chogm.kboScore`
- `APNS_PRIVATE_KEY_PATH`
- `KBO_PUSH_ENABLED=true`

The production profile disables Swagger/OpenAPI UI, keeps automatic sync opt-in
through `APP_SYNC_ENABLED`, and defaults APNs to the production gateway. Startup
fails under the production profile when sync is enabled unless `KBO_PUSH_ENABLED=true`, `APNS_ENV=production`,
`APNS_BUNDLE_ID=com.chogm.kboScore`, `APNS_TEAM_ID`, `APNS_KEY_ID`, and
`APNS_PRIVATE_KEY_PATH` are all configured.

## OpenAPI and Swagger UI

When the app is running locally, inspect the documented public API surface at:

- Swagger UI: `http://localhost:8088/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8088/v3/api-docs`
- OpenAPI YAML: `http://localhost:8088/v3/api-docs.yaml`

Policy:

- App-facing `GET /api/v1/*` endpoints are included in OpenAPI.
- Internal `/internal/*` import and orchestration endpoints are intentionally hidden from Swagger UI so they are not casually used as public API operations.
- Public game payloads now include `cancelReason`:
  - cancelled + explicit `우천취소` -> `rain`
  - cancelled + explicit `그라운드사정` -> `ground`
  - cancelled + no clear official reason -> `unknown`
  - non-cancelled games -> `null`

## Trigger schedule ingestion

Use the internal verification endpoint to fetch and upsert a month of official KBO schedule data:

```bash
curl -X POST 'http://localhost:8088/internal/schedule/import?year=2026&month=4'
```

## Trigger game detail ingestion

Use the internal verification endpoint to fetch and persist the latest snapshot and line score data for one imported game:

```bash
curl -X POST 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/import'
```

To validate repeated same-game refresh behavior without a scheduler, use:

```bash
curl -X POST 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/refresh?repeat=3'
```

When the source is unchanged, later runs should report `snapshotCreated=false` and `lineScoresUpdated=false`.

## Run one orchestration pass

Use the internal orchestration endpoint to evaluate a date's games and optionally execute one controlled detail-refresh pass:

```bash
curl -X POST 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=false'
curl -X POST 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=true'
```

## Sync scheduler

The sync scheduler is production-safe by default:

- `app.sync.enabled=false` by default, so normal startup performs no automatic refresh.
- Runtime writer jobs require `APP_RUNTIME_ROLE=writer` plus the profile's allowed schema.
- `LiveGameSyncScheduler` is the single automatic scheduler. It handles pregame, live, and post-final confirmation cadence from game state.
- Legacy `app.scheduler.*`, `APP_SCHEDULER_*`, `app.live-sync.enabled`, and old live-sync enabled env vars are not used as scheduler switches.

Environment policy:

- `local` / `development`: reader or unset role is safe only with `APP_SYNC_ENABLED=false`. Use `APP_RUNTIME_ROLE=writer` with `kbo_crawler_api_dev` for controlled write testing. The production schema is always blocked.
- `test`: reader by default. The production schema is always blocked. Writer tests must explicitly use `APP_RUNTIME_ROLE=writer` with `kbo_crawler_api_dev`.
- `production`: writer jobs require `APP_RUNTIME_ROLE=writer` with `kbo_crawler_api`. The development schema is blocked.

Local reader example:

```env
SPRING_PROFILES_ACTIVE=local
APP_DB_SCHEMA=kbo_crawler_api_dev
APP_RUNTIME_ROLE=reader
APP_SYNC_ENABLED=false
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/postgres?sslmode=require&currentSchema=kbo_crawler_api_dev
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<password>
```

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Local writer example:

```env
SPRING_PROFILES_ACTIVE=local
APP_DB_SCHEMA=kbo_crawler_api_dev
APP_RUNTIME_ROLE=writer
APP_SYNC_ENABLED=true
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:5432/postgres?sslmode=require&currentSchema=kbo_crawler_api_dev
SPRING_DATASOURCE_USERNAME=<user>
SPRING_DATASOURCE_PASSWORD=<password>
```

Writer mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=local --app.sync.enabled=true'
```
