# KBO Crawler API

## Local setup

1. Create a repository-root `.env` file by copying [.env.example](/Users/chogyeongmin/develop/kbo_back/.env.example).
2. Set at least these keys in `.env`:
   - `SPRING_DATASOURCE_USERNAME`
   - `SPRING_DATASOURCE_PASSWORD`
3. Optionally override:
   - `SPRING_DATASOURCE_URL`
   - `SPRING_PROFILES_ACTIVE`

The application loads `.env` automatically through Spring Boot config import when you run from the repository root.
Flyway and JPA use the dedicated `kbo_crawler_api` schema inside the `kbo` database so the app does not collide with unrelated tables in `public`.

## Run locally

```bash
./gradlew build
./gradlew bootRun
```

## Production profile

Use `SPRING_PROFILES_ACTIVE=production` for App Store production runtime. Start from
`.env.production.example`, then set the real database and APNs values outside Git:

- `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`
- `APNS_ENV=production`
- `APNS_TEAM_ID`, `APNS_KEY_ID`, `APNS_BUNDLE_ID=com.chogm.kboScore`
- `APNS_PRIVATE_KEY_PATH`
- `KBO_PUSH_ENABLED=true`

The production profile disables Swagger/OpenAPI UI, keeps scheduler phases opt-in,
and defaults APNs to the production gateway. Push defaults to disabled. When
`KBO_PUSH_ENABLED=true`, startup requires `APNS_ENV=production`,
`APNS_BUNDLE_ID=com.chogm.kboScore`, `APNS_TEAM_ID`, `APNS_KEY_ID`, and
`APNS_PRIVATE_KEY_PATH`. The Docker runtime uses UID/GID `10001`; mounted APNs
key files must be readable by that user.

## Security and compatibility

- Set `ADMIN_API_KEY` outside Git. Every `/admin` and `/internal` operation requires
  `X-Admin-Api-Key`; an unset key denies all such requests. Configure trusted
  ingestion jobs to send the same header before deploying this change.
- Both public `reconcile-stale` routes accept at most three non-null dates, from
  today back through 366 days in the application clock's timezone. One request
  is admitted every 30 seconds per application instance, with at most one running
  at a time. A busy/cooldown response is HTTP 429 with `Retry-After: 30`. Public
  calls respect detail-refresh TTL; administrators can still force refresh.
- Full APNs tokens authorize changes to their own registration. `installationId`
  and `activityId` are metadata and cannot replace or disable a different token.
  Same-token updates remain idempotent. Token rotation creates a new row; the
  existing invalid-token response handling retires old rows when APNs rejects them.
  The app ends replaced Live Activities itself. During that transition, old tokens
  are not disabled solely by an unproven installation ID.
- Apply Flyway V21/V22 with the application rollout. V21 permits separate rows for
  a Live Activity's rotated tokens while keeping full-token uniqueness; V22 supplies
  the snapshot and notification-setting columns required by Hibernate's default
  schema validation, preserving existing columns and their values.
- Spring Boot 4.0 uses patched Spring MVC 7.0. Existing API naming/date formats are
  preserved with Boot's Jackson 2 compatibility module and tested over HTTP.
  Keep `spring.http.converters.preferred-json-mapper=jackson2` until a separately
  tested Jackson 3 wire-format migration.

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
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/schedule/import?year=2026&month=4'
```

## Trigger game detail ingestion

Use the internal verification endpoint to fetch and persist the latest snapshot and line score data for one imported game:

```bash
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/import'
```

To validate repeated same-game refresh behavior without a scheduler, use:

```bash
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/games/20260401-LG-KIA/detail/refresh?repeat=3'
```

When the source is unchanged, later runs should report `snapshotCreated=false` and `lineScoresUpdated=false`.

## Run one orchestration pass

Use the internal orchestration endpoint to evaluate a date's games and optionally execute one controlled detail-refresh pass:

```bash
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=false'
curl -X POST -H "X-Admin-Api-Key: ${ADMIN_API_KEY}" 'http://localhost:8088/internal/orchestration/detail-refresh-pass?date=2026-04-09&execute=true'
```

## Scheduler shell

The scheduler shell is production-safe by default:

- `app.scheduler.enabled=false` by default, so normal startup performs no automatic refresh.
- The scheduler requires an explicit Spring profile when enabled: `local`, `dev`, `staging`, or `production`.
- `live` refresh stays disabled unless `app.scheduler.live-enabled=true` is set explicitly.
- The `local` profile forbids live refresh entirely.
- Each scheduler phase skips if the previous same-phase run is still active.
- Each scheduler phase also skips one extra interval after a failed run to avoid tight failure loops.
- Each executed scheduler pass creates one parent `crawl_jobs` row with `job_type=detail-refresh-orchestration-pass`.
  These parent rows track the pass phase, date target, selected/executed/succeeded/failed counts, and skipped count above the child `game-detail-import` rows.

Environment policy:

- `local`: safe for disabled mode, pregame-only, or controlled post-final refresh. Live refresh is blocked.
- `dev` / `staging`: safe for pregame or post-final refresh. Live refresh is allowed only when explicitly enabled.
- `production`: all phases remain opt-in by config. Live refresh is still never implicit.

Safe examples:

Disabled mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

Pregame-only mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=local --app.scheduler.enabled=true --app.scheduler.pregame-enabled=true --app.scheduler.live-enabled=false --app.scheduler.post-final-enabled=false'
```

Controlled post-final mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=dev --app.scheduler.enabled=true --app.scheduler.pregame-enabled=false --app.scheduler.live-enabled=false --app.scheduler.post-final-enabled=true'
```

Explicitly enabled live mode:

```bash
./gradlew bootRun --args='--spring.profiles.active=staging --app.scheduler.enabled=true --app.scheduler.pregame-enabled=true --app.scheduler.live-enabled=true --app.scheduler.post-final-enabled=true'
```
