# ADR 0001: Game Identity and Score Storage

- Status: Accepted
- Date: 2026-04-09

## Context

The initial project docs had three contradictions that would affect the first schema and API implementation:

1. The API examples used string-like game IDs such as `20260409-LG-SSG`, while the ERD defined `games.id` as a UUID.
2. The API examples showed scheduled-game scores as `null`, while the ERD defined game and snapshot scores as `NOT NULL DEFAULT 0`.
3. The ERD defined `line_scores.inning_number` as `NOT NULL`, while the ERD and API notes also implied inning numbers might be null.

These needed to be resolved before bootstrapping the backend so that IDs, schema, and response contracts remain stable.

## Decision

### 1. Game ID strategy

- The database primary key remains `games.id UUID`.
- The app-facing `gameId` is a separate normalized public identifier stored as `games.public_game_id`.
- API payload field `id` and path parameter `{gameId}` both refer to `public_game_id`.
- `provider_game_id` remains the upstream provider mapping key and must not be used as the app-facing ID.

### 2. Scheduled-game score nullability

- `games.home_score` and `games.away_score` are nullable.
- `game_snapshots.home_score` and `game_snapshots.away_score` are nullable.
- Scheduled games return `null` scores until the source provides a real score.
- `0` is stored only when the source explicitly reports `0`, not as a synthetic default for pregame state.

### 3. Line score inning numbering

- `line_scores.inning_number` is required and never null.
- The line score table stores only innings with known inning numbers.
- If the upstream payload is missing inning numbering, that data is retained only in raw archive or diagnostic snapshot data until it can be normalized safely.

## Consequences

- Database joins and internal references can remain UUID-based without exposing UUIDs to app clients.
- API schedule responses can distinguish between `not started` and a real `0-0` score.
- The line score table keeps a clean uniqueness rule on `(game_id, inning_number)` and avoids ambiguous rows.
- Future services must translate from public game ID to UUID internally when resolving a game from an API request.
