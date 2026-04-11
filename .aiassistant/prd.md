# KBO Crawler API PRD

## Project Name
**KBO Crawler API**

## Document Purpose
This document defines the product requirements for a standalone backend project that periodically collects KBO schedule, game detail, and scoreboard data, normalizes it, stores it in PostgreSQL, and exposes stable read APIs for app/internal consumers.

---

## 1. Background

The app needs a dedicated data collection system to keep game data current and consistent without requiring app releases for schedule or score changes.

Current needs include:
- reflecting external data changes without shipping a new app build
- supporting shorter refresh intervals during live games
- keeping Home, Schedule, and Game Detail aligned on the same game time and status
- avoiding immediate app breakage when the external source is temporarily unavailable or changes structure

Because of this, the collection/API layer should exist as a **separate backend project** from the app itself.

---

## 2. Goals

### Core Goals
1. Collect KBO game data on a schedule.
2. Normalize and store the collected data in PostgreSQL.
3. Provide a stable internal/app-facing read API.
4. Remain reliable even when polling frequently during live games.
5. Preserve last known good data when external collection fails.

### Success Criteria
- No collection runs on days with no games.
- Polling intervals change correctly based on game state.
- Home, Schedule, and Game Detail can rely on the same normalized game time/state.
- A collection failure does not immediately cause app-facing API failure.
- Repeated identical source data does not cause unnecessary DB writes.

---

## 3. Scope

### In Scope
- daily game schedule collection
- game status collection
- game detail collection
- line score collection
- extensible support for preview/review/event data
- PostgreSQL persistence
- app/internal read APIs
- state-based scheduling
- retry/backoff/duplicate-run prevention/stale-data handling

### Out of Scope
- modifying the existing iOS app in this project
- admin web UI
- large analytics dashboards
- user authentication/authorization
- browser automation as the default collection method

---

## 4. Users

### Primary Users
- iOS app
- internal backend/ops tools

### Secondary Users
- developers
- QA
- operators

---

## 5. System Overview

### Architecture
- separate backend project
- Spring Boot based
- existing PostgreSQL database
- default DB URL:

`jdbc:postgresql://localhost:55432/kbo`

### Basic Flow
1. Scheduler decides whether collection should run based on game state.
2. Collector fetches KBO source data.
3. Parser extracts structured values.
4. Normalizer converts provider data into internal models.
5. DB layer upserts normalized data.
6. App/internal consumers read only from this API, not directly from KBO.

---

## 6. Functional Requirements

## 6.1 Schedule Collection
The system must collect game listings by date.

### Example Fields
- public game ID
- game date
- start time
- home team / away team
- stadium
- game status
- provider game ID
- cancellation/postponement flags
- source updated at

### Requirements
- duplicate storage for the same game must be prevented
- the app-facing `gameId` must be a stable normalized public key, while the database may use a separate internal UUID
- changed start times must be updated
- hardcoded weekend time rules must never be used
- only real collected data may drive stored times

---

## 6.2 Game Detail Collection
The system must be able to collect individual game detail data.

### Included Data
- current score
- inning state
- line score
- balls / strikes / outs
- base occupancy
- final score
- expandable structure for preview / review / event data

### Requirements
- missing fields must not automatically fail the full collection job
- live and final games may have different detail shapes
- scheduled games must keep score fields as `null` until a real score is available from the source
- final state must remain durable once final data is confirmed

---

## 6.3 Read APIs
The system must provide APIs that are easy for the app to consume.

### Minimum APIs
- `GET /api/v1/games?date=YYYY-MM-DD`
- `GET /api/v1/games/month?year=YYYY&month=MM`
- `GET /api/v1/games/{gameId}`
- `GET /api/v1/games/{gameId}/linescore`
- `GET /api/v1/scoreboard`

### Requirements
- raw provider field names must not be exposed directly
- responses must be normalized and app-friendly
- nullable fields must be handled explicitly
- operational metadata such as `updatedAt` and `isStale` should be supported where useful

---

## 7. Collection Policy

## 7.1 No Games
- Do not collect.

### Condition
- no games exist for the date

---

## 7.2 Game Day, Before First Pitch
- Poll every **30 minutes**

### Purpose
- schedule updates
- start-time changes
- cancellation/postponement changes
- stadium changes
- preview/probable-starter style data if available

---

## 7.3 Live Games
- Poll every **10 to 15 seconds**

### Rules
- prioritize only games that are actually live
- do not repeatedly fetch all games for the date without reason

### Purpose
- score updates
- inning state
- line score
- major events
- live game detail freshness

---

## 7.4 Immediately After Final
- Poll every **1 minute**
- Stop when final data has been confirmed

### Minimum Final Data Criteria
- game status is final
- final score exists
- line score exists

### Recommended Additional Criteria
- no meaningful data change across 2 to 3 consecutive polls

### Safety Requirement
- infinite polling is not allowed
- a max retry/poll limit must exist

---

## 8. Non-Functional Requirements

## 8.1 Reliability
- external source failure must not directly bring down the internal API
- last known good data must remain available
- stale responses must be possible when needed

## 8.2 Performance
- repeated identical data should avoid unnecessary DB updates
- polling during live games must remain stable

## 8.3 Extensibility
- preview/review/event data should be addable later without major redesign
- source-specific parsing changes should stay isolated to parser/crawler logic as much as possible

## 8.4 Maintainability
- parser / crawler / storage / scheduler / API concerns must be separated
- external HTML/response shape changes should have limited blast radius

## 8.5 Operability
- collection failures must be traceable
- operators/developers must be able to identify which game/source failed and why
- build/test results should be reportable

---

## 9. Data Storage Requirements

### Database
- use the existing PostgreSQL database
- default URL:

`jdbc:postgresql://localhost:55432/kbo`

### Storage Principles
- check for conflicts with existing schema/tables before changes
- manage schema through migrations
- prefer upsert-friendly design
- support separation between raw source data and normalized application data where needed
- use a separate public game identifier for API paths and payloads if the database primary key is UUID-based
- store only numbered innings in `line_scores`; if the upstream source omits inning numbers, keep that trace in raw/snapshot data instead of persisting ambiguous line score rows

### Core Data Concepts
- games
- teams
- game_snapshots
- line_scores
- game_events
- crawl_jobs
- crawl_failures
- crawl_raw_archive

---

## 10. Architecture Requirements

### Recommended Package Layout
- `config`
- `domain`
- `repository`
- `crawler`
- `parser`
- `service`
- `scheduler`
- `api`
- `support`

### Implementation Principles
- the project already exists, so read current structure first
- prefer extending/reusing current structures over creating duplicates
- avoid duplicate models/services for the same responsibility
- reuse the current build, DB, migration, and scheduling conventions if they already exist

---

## 11. Error Handling and Operational Policies

### Failure Handling
- all external calls must have timeouts
- retries must be bounded
- backoff should be used where appropriate
- last known good data must remain available on collection failure

### Duplicate Prevention
- concurrent duplicate collection for the same `gameId` must be prevented
- lock/job-guard style protection is required

### Data Quality
- partial success is acceptable when source fields are missing
- parser failures must be diagnosable
- fabricated/guessed values are not allowed

---

## 12. Milestones

## Phase 1
- inspect current project structure
- confirm datasource/application configuration
- implement/stabilize daily game list collection
- upsert games
- provide schedule/home-facing read APIs

## Phase 2
- implement game detail collection
- persist line score
- implement state-driven polling
- implement post-final automatic stop logic

## Phase 3
- expand preview/review/event support
- add raw archive
- improve retry/backoff/locking
- introduce Redis if needed

## Phase 4
- add metrics
- add health checks
- add manual recollect/admin endpoints
- add alerting

---

## 13. Acceptance Criteria

The first major delivery is considered successful when:

1. no collection runs on no-game days
2. pregame 30-minute polling works
3. live-game 10 to 15 second polling works
4. post-final 1-minute polling works
5. polling stops after final data is confirmed
6. schedule APIs return real game times
7. the same game is not stored as duplicate records
8. last known good data remains readable after collection failure
9. app consumers can rely on the internal API instead of the external site
10. the project builds and the main paths have documented validation results

---

## 14. Risks

### Confirmed Risks
- external site structure changes
- external response delay or temporary blocking
- delayed appearance of final postgame data
- mismatch risk when stale bootstrap/local data exists elsewhere

### Pre-Production Checks Still Needed
- robots.txt / terms of use / allowed access boundaries
- schema conflicts with existing DB
- whether the current project already has migration/locking/retry patterns that must be reused

---

## 15. Assumptions

The following assumptions are currently in scope:
- the project has already been created
- the existing PostgreSQL `kbo` database will be reused
- the app will eventually consume this API as its source of truth
- live polling at 10 to 15 seconds is operationally acceptable
- post-final polling ends after final data is confirmed

---

## 16. One-Line Summary
This project is a **standalone KBO data collection and read API backend** that polls based on game state, normalizes data, stores it safely, and exposes a stable contract the app can trust.
