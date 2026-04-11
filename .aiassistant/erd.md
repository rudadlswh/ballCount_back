# KBO Crawler API ERD Draft

## Purpose
This ERD defines the core database structure for the KBO Crawler API project.

The database is designed to support:
- normalized game schedule storage
- live/final game state storage
- line score storage
- event timeline storage
- collection job tracking
- collection failure tracking
- optional raw source archiving

The database should support:
- stable app-facing reads
- state-based polling
- upsert-friendly writes
- traceable collection failures
- preservation of last-known-good data

---

# 1. Entity Overview

Core entities:

- `teams`
- `games`
- `game_snapshots`
- `line_scores`
- `game_events`
- `crawl_jobs`
- `crawl_failures`
- `crawl_raw_archive`

Optional future entities:

- `game_reviews`
- `game_previews`
- `source_mappings`

---

# 2. Tables

## 2.1 teams
Stores normalized team master data.

### Columns
- `id` UUID PK
- `team_code` VARCHAR(30) NOT NULL UNIQUE
- `name` VARCHAR(100) NOT NULL
- `short_name` VARCHAR(50) NOT NULL
- `english_name` VARCHAR(100)
- `logo_url` TEXT
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Notes
- `team_code` should be the stable internal key used across the API, for example `lg`, `ssg`, `kia`.
- Team naming should be normalized here instead of repeating raw provider names everywhere.

---

## 2.2 games
Stores one row per game as the primary normalized game record.

### Columns
- `id` UUID PK
- `public_game_id` VARCHAR(50) NOT NULL UNIQUE
- `provider` VARCHAR(30) NOT NULL
- `provider_game_id` VARCHAR(100) NOT NULL
- `game_date` DATE NOT NULL
- `scheduled_at` TIMESTAMP WITH TIME ZONE
- `stadium` VARCHAR(100)
- `status` VARCHAR(30) NOT NULL DEFAULT 'unknown'
- `home_team_id` UUID NOT NULL FK -> teams(id)
- `away_team_id` UUID NOT NULL FK -> teams(id)
- `home_score` INTEGER
- `away_score` INTEGER
- `inning_state` VARCHAR(50)
- `is_cancelled` BOOLEAN NOT NULL DEFAULT false
- `is_postponed` BOOLEAN NOT NULL DEFAULT false
- `source_updated_at` TIMESTAMP WITH TIME ZONE
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Constraints
- UNIQUE (`public_game_id`)
- UNIQUE (`provider`, `provider_game_id`)

### Notes
- This is the main source-of-truth table for schedule/home/detail summary views.
- `id` is the internal relational key. App-facing APIs should use `public_game_id`.
- `scheduled_at` must always come from normalized real source data, not hardcoded weekday/weekend rules.
- `home_score` and `away_score` should remain null for scheduled games until the source reports a real score.
- `status` should use normalized values such as:
  - `scheduled`
  - `live`
  - `final`
  - `postponed`
  - `cancelled`
  - `suspended`
  - `unknown`

---

## 2.3 game_snapshots
Stores point-in-time or latest-state game detail data for live/final tracking.

### Columns
- `id` UUID PK
- `game_id` UUID NOT NULL FK -> games(id)
- `inning` INTEGER
- `inning_half` VARCHAR(10)
- `inning_label` VARCHAR(50)
- `balls` INTEGER
- `strikes` INTEGER
- `outs` INTEGER
- `runner_on_first` BOOLEAN NOT NULL DEFAULT false
- `runner_on_second` BOOLEAN NOT NULL DEFAULT false
- `runner_on_third` BOOLEAN NOT NULL DEFAULT false
- `home_score` INTEGER
- `away_score` INTEGER
- `home_hits` INTEGER
- `away_hits` INTEGER
- `home_errors` INTEGER
- `away_errors` INTEGER
- `home_balls` INTEGER
- `away_balls` INTEGER
- `raw_hash` VARCHAR(128)
- `source_updated_at` TIMESTAMP WITH TIME ZONE
- `fetched_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Index Suggestions
- INDEX (`game_id`, `fetched_at` DESC)
- INDEX (`game_id`, `source_updated_at` DESC)

### Notes
- This table can keep a snapshot history, not just the latest state.
- If only latest-state persistence is desired initially, a retention policy can be added later.
- `raw_hash` helps skip unnecessary writes for unchanged source content.
- Score fields may remain null for partial or scheduled snapshots until a real score is available.

---

## 2.4 line_scores
Stores inning-by-inning scoring for a game.

### Columns
- `id` UUID PK
- `game_id` UUID NOT NULL FK -> games(id)
- `inning_number` INTEGER NOT NULL
- `away_runs` INTEGER
- `home_runs` INTEGER
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Constraints
- UNIQUE (`game_id`, `inning_number`)

### Notes
- Supports extra innings naturally.
- `inning_number` must always be known for persisted line score rows.
- If the source exposes partial line score data without inning numbers, keep that trace in raw archive or snapshot data instead of inserting ambiguous rows here.
- This table is used by Game Detail line score APIs.

---

## 2.5 game_events
Stores normalized event/timeline items for a game.

### Columns
- `id` UUID PK
- `game_id` UUID NOT NULL FK -> games(id)
- `provider_event_id` VARCHAR(100)
- `sequence_number` INTEGER NOT NULL
- `inning` INTEGER
- `inning_half` VARCHAR(10)
- `event_type` VARCHAR(50)
- `event_text` TEXT NOT NULL
- `occurred_at` TIMESTAMP WITH TIME ZONE
- `source_updated_at` TIMESTAMP WITH TIME ZONE
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Constraints
- UNIQUE (`game_id`, `sequence_number`)

### Index Suggestions
- INDEX (`game_id`, `sequence_number`)
- INDEX (`game_id`, `occurred_at`)

### Notes
- `provider_event_id` is optional because some sources may not expose stable event IDs.
- `sequence_number` is the normalized ordering key.
- Event text must come from real source data only. No fabricated text.

---

## 2.6 crawl_jobs
Tracks collection job execution.

### Columns
- `id` UUID PK
- `job_type` VARCHAR(50) NOT NULL
- `target_type` VARCHAR(50) NOT NULL
- `target_key` VARCHAR(100) NOT NULL
- `status` VARCHAR(30) NOT NULL
- `scheduled_at` TIMESTAMP WITH TIME ZONE
- `started_at` TIMESTAMP WITH TIME ZONE
- `finished_at` TIMESTAMP WITH TIME ZONE
- `retry_count` INTEGER NOT NULL DEFAULT 0
- `last_error_message` TEXT
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Example Values
- `job_type`
  - `daily-schedule-poll`
  - `live-game-poll`
  - `post-final-poll`
- `target_type`
  - `date`
  - `game`
- `status`
  - `queued`
  - `running`
  - `succeeded`
  - `failed`
  - `cancelled`
  - `skipped`

### Notes
- This table supports operational visibility and debugging.
- `target_key` might be a date like `2026-04-09` or a public game ID such as `20260409-LG-SSG`.

---

## 2.7 crawl_failures
Stores detailed failure records for collection/parsing/persistence issues.

### Columns
- `id` UUID PK
- `crawl_job_id` UUID FK -> crawl_jobs(id)
- `provider` VARCHAR(30)
- `target_type` VARCHAR(50)
- `target_key` VARCHAR(100)
- `failure_stage` VARCHAR(50) NOT NULL
- `error_code` VARCHAR(100)
- `error_message` TEXT NOT NULL
- `stack_trace` TEXT
- `occurred_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Example `failure_stage` values
- `request`
- `parse`
- `normalize`
- `persist`
- `publish`

### Notes
- This table is separate from `crawl_jobs` so that multiple failure records can exist per job if needed.
- Useful for monitoring and handoff documentation.

---

## 2.8 crawl_raw_archive
Stores raw upstream responses or traceable raw metadata.

### Columns
- `id` UUID PK
- `provider` VARCHAR(30) NOT NULL
- `resource_type` VARCHAR(50) NOT NULL
- `target_key` VARCHAR(100)
- `source_url` TEXT NOT NULL
- `http_status` INTEGER
- `content_type` VARCHAR(100)
- `body_hash` VARCHAR(128)
- `body_text` TEXT
- `fetched_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Index Suggestions
- INDEX (`provider`, `resource_type`, `target_key`)
- INDEX (`fetched_at` DESC)

### Notes
- `body_text` may be omitted or truncated depending on storage policy.
- At minimum, `body_hash` and metadata should be stored for debugging and change detection.
- Useful when the provider changes HTML/response shape.

---

# 3. Optional Future Tables

## 3.1 game_reviews
Stores structured postgame review/summary information.

### Columns
- `id` UUID PK
- `game_id` UUID NOT NULL FK -> games(id)
- `winning_pitcher_name` VARCHAR(100)
- `losing_pitcher_name` VARCHAR(100)
- `save_pitcher_name` VARCHAR(100)
- `summary_text` TEXT
- `review_payload` JSONB
- `source_updated_at` TIMESTAMP WITH TIME ZONE
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Notes
- Can be added later without changing the core schedule/live pipeline.

---

## 3.2 game_previews
Stores structured pregame preview data.

### Columns
- `id` UUID PK
- `game_id` UUID NOT NULL FK -> games(id)
- `home_probable_starter` VARCHAR(100)
- `away_probable_starter` VARCHAR(100)
- `preview_text` TEXT
- `preview_payload` JSONB
- `source_updated_at` TIMESTAMP WITH TIME ZONE
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
- `updated_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Notes
- Intended for scheduled games only.
- Optional for later phases.

---

## 3.3 source_mappings
Stores provider-specific mapping keys if multiple upstream sources are introduced later.

### Columns
- `id` UUID PK
- `provider` VARCHAR(30) NOT NULL
- `provider_entity_type` VARCHAR(50) NOT NULL
- `provider_entity_id` VARCHAR(100) NOT NULL
- `internal_entity_type` VARCHAR(50) NOT NULL
- `internal_entity_id` UUID NOT NULL
- `created_at` TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()

### Constraints
- UNIQUE (`provider`, `provider_entity_type`, `provider_entity_id`)

### Notes
- Not required if there is only one stable provider.
- Useful if additional data sources are added later.

---

# 4. Relationships

## Core Relationships
- `teams (1) -> (N) games as home_team_id`
- `teams (1) -> (N) games as away_team_id`
- `games (1) -> (N) game_snapshots`
- `games (1) -> (N) line_scores`
- `games (1) -> (N) game_events`
- `crawl_jobs (1) -> (N) crawl_failures`

## Optional Relationships
- `games (1) -> (0..1) game_reviews`
- `games (1) -> (0..1) game_previews`

---

# 5. Cardinality Summary

- One team can appear in many games.
- One game has exactly one home team and one away team.
- One game can have many live/final snapshots over time.
- One game can have many innings in line score.
- One game can have many event items.
- One collection job can produce many failure records.
- One game may later have one preview row and one review row.

---

# 6. Suggested Initial Keys and Constraints

## teams
- PK: `id`
- UK: `team_code`

## games
- PK: `id`
- UK: `public_game_id`
- UK: (`provider`, `provider_game_id`)

## line_scores
- PK: `id`
- UK: (`game_id`, `inning_number`)

## game_events
- PK: `id`
- UK: (`game_id`, `sequence_number`)

---

# 7. Suggested Indexes

## games
- INDEX (`game_date`)
- INDEX (`status`)
- INDEX (`public_game_id`)
- INDEX (`home_team_id`)
- INDEX (`away_team_id`)
- INDEX (`scheduled_at`)
- INDEX (`provider`, `provider_game_id`)

## game_snapshots
- INDEX (`game_id`, `fetched_at` DESC)

## line_scores
- INDEX (`game_id`, `inning_number`)

## game_events
- INDEX (`game_id`, `sequence_number`)
- INDEX (`game_id`, `occurred_at`)

## crawl_jobs
- INDEX (`status`)
- INDEX (`job_type`, `target_type`, `target_key`)
- INDEX (`scheduled_at`)

## crawl_failures
- INDEX (`crawl_job_id`)
- INDEX (`occurred_at` DESC)

## crawl_raw_archive
- INDEX (`provider`, `resource_type`, `target_key`)
- INDEX (`fetched_at` DESC)

---

# 8. Initial Design Notes

## Confirmed Design Direction
- `games` is the primary normalized table.
- `scheduled_at` is the shared source of truth for Home/Schedule/API time display.
- `game_snapshots` stores live/final status progression over time.
- `line_scores` and `game_events` are separated for simpler reads and safer partial updates.
- operational tables are separated from app-facing tables

## Important Rules
- Do not hardcode game times.
- Do not overwrite good data with worse partial fallback data without reason.
- Preserve source traceability wherever practical.
- Normalize statuses and team identity consistently.

---

# 9. Simplified Relationship Diagram

```text
teams
 ├─< games >─ teams
       ├─< game_snapshots
       ├─< line_scores
       ├─< game_events
       ├─o game_reviews
       └─o game_previews

crawl_jobs
 └─< crawl_failures

crawl_raw_archive
  (independent trace/archive table)
