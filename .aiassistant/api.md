# KBO Crawler API Specification

## Version
**v1**

## Base URL
`/api/v1`

## Purpose
This API provides normalized KBO game data collected by the KBO Crawler API backend.

The API is designed for:
- iOS app consumption
- internal service consumption
- reliable access to normalized schedule, scoreboard, and game detail data

This API must not expose raw provider field names directly.

---

# 1. General Rules

## 1.1 Response Principles
- All timestamps should be returned in ISO-8601 format where possible.
- All date-only values should use `YYYY-MM-DD`.
- App-facing fields must be normalized and stable.
- Missing data should be represented explicitly as `null` where appropriate.
- The API may return stale but last-known-good data if the external source is temporarily unavailable.

## 1.2 Common Metadata
Where useful, responses may include:
- `updatedAt`: when this API payload was last generated or refreshed internally
- `sourceUpdatedAt`: last known update time from the upstream source
- `isStale`: whether the payload is based on stale fallback data

## 1.3 Content Type
`application/json`

## 1.4 Game Identifier Strategy
- API payload field `id` and path parameter `{gameId}` refer to a normalized public game identifier such as `20260409-LG-SSG`.
- The database may use a separate internal UUID primary key for relational integrity.
- `providerGameId` is the upstream provider mapping key and is not the app-facing identifier.

---

# 2. Common Enums

## 2.1 Game Status
Possible normalized values:

- `scheduled`
- `live`
- `final`
- `postponed`
- `cancelled`
- `suspended`
- `unknown`

## 2.2 Half Inning
Possible values:

- `top`
- `bottom`

---

# 3. Common Error Response

## Error Response Shape
```json
{
  "timestamp": "2026-04-09T19:20:30+09:00",
  "status": 400,
  "error": "Bad Request",
  "code": "INVALID_PARAMETER",
  "message": "date must be in YYYY-MM-DD format",
  "path": "/api/v1/games"
}
```
# 4. Data Models
## 4.1 TeamSummary
{
  "id": "lg",
  "name": "LG Twins",
  "shortName": "LG",
  "logoUrl": "https://example.com/logos/lg.png"
}
## 4.2 GameSummary
```json

{
  "id": "20260409-LG-SSG",
  "provider": "kbo",
  "providerGameId": "20260409LGSS0",
  "gameDate": "2026-04-09",
  "scheduledAt": "2026-04-09T18:30:00+09:00",
  "stadium": "Jamsil",
  "status": "scheduled",
  "isCancelled": false,
  "isPostponed": false,
  "awayTeam": {
    "id": "ssg",
    "name": "SSG Landers",
    "shortName": "SSG",
    "logoUrl": "https://example.com/logos/ssg.png"
  },
  "homeTeam": {
    "id": "lg",
    "name": "LG Twins",
    "shortName": "LG",
    "logoUrl": "https://example.com/logos/lg.png"
  },
  "awayScore": null,
  "homeScore": null,
  "updatedAt": "2026-04-09T18:00:00+09:00",
  "sourceUpdatedAt": "2026-04-09T17:58:14+09:00",
  "isStale": false
}
```

Notes
- `id` is the public normalized game identifier used by app clients.
- `awayScore` and `homeScore` should remain `null` for scheduled games until a real score is available from the source.

## 4.3 GameState
```json
{
  "inning": 8,
  "half": "top",
  "inningLabel": "Top 8",
  "balls": 2,
  "strikes": 1,
  "outs": 1,
  "bases": {
    "first": true,
    "second": false,
    "third": true
  }
}
```
## 4.4 LineScoreInning
```json
{
  "inning": 1,
  "awayRuns": 0,
  "homeRuns": 1
}
```
## 4.5 GameTotals
```json
{
  "away": {
    "runs": 3,
    "hits": 8,
    "errors": 1,
    "balls": 4
  },
  "home": {
    "runs": 5,
    "hits": 10,
    "errors": 0,
    "balls": 6
  }
}
```
## 4.6 GameEvent
```json
{
  "id": "evt-001",
  "sequence": 120,
  "inning": 8,
  "half": "top",
  "eventType": "score",
  "text": "Kim Hyun-soo hits an RBI single to right field.",
  "occurredAt": "2026-04-09T20:11:10+09:00"
}
```
# 5. Endpoints
## 5.1 Get Games By Date
```json
GET /games

Returns all games for a specific date.

Query Parameters
date (required): YYYY-MM-DD
teamId (optional): filter by team
status (optional): filter by normalized game status
Example Request

GET /api/v1/games?date=2026-04-09

Example Response
{
  "date": "2026-04-09",
  "games": [
    {
      "id": "20260409-LG-SSG",
      "provider": "kbo",
      "providerGameId": "20260409LGSS0",
      "gameDate": "2026-04-09",
      "scheduledAt": "2026-04-09T18:30:00+09:00",
      "stadium": "Jamsil",
      "status": "scheduled",
      "isCancelled": false,
      "isPostponed": false,
      "awayTeam": {
        "id": "ssg",
        "name": "SSG Landers",
        "shortName": "SSG",
        "logoUrl": "https://example.com/logos/ssg.png"
      },
      "homeTeam": {
        "id": "lg",
        "name": "LG Twins",
        "shortName": "LG",
        "logoUrl": "https://example.com/logos/lg.png"
      },
      "awayScore": null,
      "homeScore": null,
      "updatedAt": "2026-04-09T18:00:00+09:00",
      "sourceUpdatedAt": "2026-04-09T17:58:14+09:00",
      "isStale": false
    }
  ],
  "updatedAt": "2026-04-09T18:00:00+09:00",
  "isStale": false
}
```
Notes
If there are no games, return 200 OK with an empty games array.
Do not return 404 for a valid date with no games.
## 5.2 Get Games By Month
```json
GET /games/month

Returns all games for a given month.

Query Parameters
year (required): YYYY
month (required): 1-12
teamId (optional): filter by team
Example Request

GET /api/v1/games/month?year=2026&month=4

Example Response
{
  "year": 2026,
  "month": 4,
  "games": [
    {
      "id": "20260409-LG-SSG",
      "provider": "kbo",
      "providerGameId": "20260409LGSS0",
      "gameDate": "2026-04-09",
      "scheduledAt": "2026-04-09T18:30:00+09:00",
      "stadium": "Jamsil",
      "status": "scheduled",
      "isCancelled": false,
      "isPostponed": false,
      "awayTeam": {
        "id": "ssg",
        "name": "SSG Landers",
        "shortName": "SSG",
        "logoUrl": "https://example.com/logos/ssg.png"
      },
      "homeTeam": {
        "id": "lg",
        "name": "LG Twins",
        "shortName": "LG",
        "logoUrl": "https://example.com/logos/lg.png"
      },
      "awayScore": null,
      "homeScore": null,
      "updatedAt": "2026-04-09T18:00:00+09:00",
      "sourceUpdatedAt": "2026-04-09T17:58:14+09:00",
      "isStale": false
    }
  ],
  "updatedAt": "2026-04-09T18:00:00+09:00",
  "isStale": false
}
```
## 5.3 Get Scoreboard
```json
GET /scoreboard

Returns a scoreboard-style view for a date.

Query Parameters
date (optional): defaults to current KST date if omitted
Example Request

GET /api/v1/scoreboard?date=2026-04-09

Example Response
{
  "date": "2026-04-09",
  "games": [
    {
      "id": "20260409-LG-SSG",
      "status": "live",
      "scheduledAt": "2026-04-09T18:30:00+09:00",
      "stadium": "Jamsil",
      "awayTeam": {
        "id": "ssg",
        "name": "SSG Landers",
        "shortName": "SSG",
        "logoUrl": "https://example.com/logos/ssg.png"
      },
      "homeTeam": {
        "id": "lg",
        "name": "LG Twins",
        "shortName": "LG",
        "logoUrl": "https://example.com/logos/lg.png"
      },
      "awayScore": 3,
      "homeScore": 4,
      "state": {
        "inning": 8,
        "half": "top",
        "inningLabel": "Top 8",
        "balls": 2,
        "strikes": 1,
        "outs": 1,
        "bases": {
          "first": true,
          "second": false,
          "third": true
        }
      }
    }
  ],
  "updatedAt": "2026-04-09T20:12:00+09:00",
  "isStale": false
}
```
Notes
Intended for Home and live scoreboard surfaces.
May omit fields that are only relevant to detail pages.
## 5.4 Get Game Detail
```json
GET /games/{gameId}

Returns a full normalized detail response for a single game.

Path Parameters
gameId (required): public normalized game ID
Example Request

GET /api/v1/games/20260409-LG-SSG

Example Response
{
  "id": "20260409-LG-SSG",
  "provider": "kbo",
  "providerGameId": "20260409LGSS0",
  "gameDate": "2026-04-09",
  "scheduledAt": "2026-04-09T18:30:00+09:00",
  "stadium": "Jamsil",
  "status": "live",
  "isCancelled": false,
  "isPostponed": false,
  "awayTeam": {
    "id": "ssg",
    "name": "SSG Landers",
    "shortName": "SSG",
    "logoUrl": "https://example.com/logos/ssg.png"
  },
  "homeTeam": {
    "id": "lg",
    "name": "LG Twins",
    "shortName": "LG",
    "logoUrl": "https://example.com/logos/lg.png"
  },
  "awayScore": 3,
  "homeScore": 4,
  "state": {
    "inning": 8,
    "half": "top",
    "inningLabel": "Top 8",
    "balls": 2,
    "strikes": 1,
    "outs": 1,
    "bases": {
      "first": true,
      "second": false,
      "third": true
    }
  },
  "winningPitcher": null,
  "losingPitcher": null,
  "savePitcher": null,
  "updatedAt": "2026-04-09T20:12:00+09:00",
  "sourceUpdatedAt": "2026-04-09T20:11:47+09:00",
  "isStale": false
}
```
Notes
For scheduled games, live-only fields may be null.
For final games, state fields may be null while result fields are populated.
Detail response should not fail only because some optional sections are missing.

## 5.5 Get Line Score
```json
GET /games/{gameId}/linescore

Returns inning-by-inning scoring plus totals.

Example Request

GET /api/v1/games/20260409-LG-SSG/linescore

Example Response
{
  "gameId": "20260409-LG-SSG",
  "innings": [
    { "inning": 1, "awayRuns": 0, "homeRuns": 1 },
    { "inning": 2, "awayRuns": 0, "homeRuns": 0 },
    { "inning": 3, "awayRuns": 2, "homeRuns": 0 },
    { "inning": 4, "awayRuns": 0, "homeRuns": 1 }
  ],
  "totals": {
    "away": {
      "runs": 3,
      "hits": 8,
      "errors": 1,
      "balls": 4
    },
    "home": {
      "runs": 5,
      "hits": 10,
      "errors": 0,
      "balls": 6
    }
  },
  "updatedAt": "2026-04-09T20:12:00+09:00",
  "isStale": false
}
```
Notes
Extra innings must be supported naturally.
Only innings with known inning numbers should be returned.
If the source is incomplete, omit ambiguous inning rows and rely on totals or stale fallback data where available.

## 5.6 Get Game Events
```json
GET /games/{gameId}/events

Returns normalized game event/timeline items if available.

Query Parameters
limit (optional): max item count
order (optional): asc or desc, default desc
Example Request

GET /api/v1/games/20260409-LG-SSG/events?limit=50&order=desc

Example Response
{
  "gameId": "20260409-LG-SSG",
  "events": [
    {
      "id": "evt-120",
      "sequence": 120,
      "inning": 8,
      "half": "top",
      "eventType": "score",
      "text": "Kim Hyun-soo hits an RBI single to right field.",
      "occurredAt": "2026-04-09T20:11:10+09:00"
    },
    {
      "id": "evt-119",
      "sequence": 119,
      "inning": 8,
      "half": "top",
      "eventType": "pitching_change",
      "text": "Pitching change: Lee Jung-yong replaces Park Myung-geun.",
      "occurredAt": "2026-04-09T20:09:02+09:00"
    }
  ],
  "updatedAt": "2026-04-09T20:12:00+09:00",
  "isStale": false
}
```
Notes
If event data is unavailable, return 200 OK with an empty events array.
Do not fabricate event text.

## 5.7 Get Home Summary
```json
GET /home/scoreboard

Returns a compact home-tab oriented payload.

Query Parameters
favoriteTeamId (optional)
Example Request

GET /api/v1/home/scoreboard?favoriteTeamId=lg

Example Response
{
  "favoriteTeamId": "lg",
  "favoriteTeamGame": {
    "id": "20260409-LG-SSG",
    "status": "live",
    "scheduledAt": "2026-04-09T18:30:00+09:00",
    "stadium": "Jamsil",
    "awayTeam": {
      "id": "ssg",
      "name": "SSG Landers",
      "shortName": "SSG",
      "logoUrl": "https://example.com/logos/ssg.png"
    },
    "homeTeam": {
      "id": "lg",
      "name": "LG Twins",
      "shortName": "LG",
      "logoUrl": "https://example.com/logos/lg.png"
    },
    "awayScore": 3,
    "homeScore": 4,
    "state": {
      "inning": 8,
      "half": "top",
      "inningLabel": "Top 8",
      "balls": 2,
      "strikes": 1,
      "outs": 1,
      "bases": {
        "first": true,
        "second": false,
        "third": true
      }
    }
  },
  "otherGames": [],
  "updatedAt": "2026-04-09T20:12:00+09:00",
  "isStale": false
}
```
Notes
This is optional but practical for app Home.
Keeps app-side aggregation simple.

# 6. Optional Internal/Operational Endpoints

These are not app-facing by default.

## 6.1 Health Check
```json
GET /internal/health

Response:

{
  "status": "UP",
  "time": "2026-04-09T20:12:00+09:00"
}
```

## 6.2 Collection Job Status
```json
GET /internal/jobs

Example response:

{
  "jobs": [
    {
      "jobType": "live-game-poll",
      "targetKey": "20260409-LG-SSG",
      "status": "running",
      "startedAt": "2026-04-09T20:11:58+09:00",
      "retryCount": 0
    }
  ]
}
```

## 6.3 Manual Recollect
```json
POST /internal/jobs/recollect

Example request:

{
  "gameId": "20260409-LG-SSG"
}

Example response:

{
  "accepted": true,
  "message": "Recollection job has been queued.",
  "gameId": "20260409-LG-SSG"
}
```

# 7. HTTP Status Rules
Success
200 OK for successful reads
202 Accepted for accepted async internal jobs
204 No Content only if intentionally used for internal actions with no body
Client Errors
400 Bad Request for invalid parameters
404 Not Found when a specific gameId does not exist
Server/Upstream Errors
500 Internal Server Error for unexpected internal failures
503 Service Unavailable only when the API truly cannot serve even stale data
Important Rule

If stale last-known-good data can still be served, prefer 200 OK with isStale=true instead of failing the request.

# 8. Stale Data Policy

When upstream collection fails but previously collected data exists:

serve last-known-good data
set isStale to true
keep updatedAt and sourceUpdatedAt accurate

This allows the app to degrade gracefully instead of failing hard.

# 9. Normalization Rules

The API must normalize:

team IDs
public game IDs
team names
game status
start times
stadium names
inning state fields
line score totals

The API must not expose inconsistent or hardcoded time values.
Schedule, Home, and Game Detail must rely on the same normalized game-time source of truth.

# 10. Versioning Rules
All app-facing endpoints live under /api/v1
Breaking changes require a new version path such as /api/v2

# 11. One-Line Summary

This API exposes normalized KBO schedule, scoreboard, and game detail data in a stable format that app clients can trust without depending directly on the external source.
