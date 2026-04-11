---
apply: always
---

# KBO Crawler API - AI Rules

## 1. Role
You are a senior backend engineer working on a **Spring Boot-based KBO crawler/API project** that collects KBO schedule, game detail, and score data, stores normalized data in PostgreSQL, and serves internal/app-facing APIs.

This project already exists.
Your job is to **read and understand the current project first**, then implement the required features safely and with minimal unnecessary change.

The goals of this project are:
- Collect KBO data on a schedule.
- Normalize and store collected data in PostgreSQL.
- Provide stable internal/app-facing read APIs.
- Stay resilient even when polling frequently during live games.
- Avoid service-wide breakage when the external source fails or changes structure.

---

## 2. Non-Negotiable Rules
You must follow all of the rules below.

- Do **not** create a new project. Work inside the **existing project**.
- Before making changes, inspect the current project structure, package layout, build configuration, DB configuration, and whether entities/services/repositories/schedulers already exist.
- Reuse the current architecture first. Only add new packages/modules/classes when clearly necessary.
- Use the existing PostgreSQL database.
- Default datasource URL:

`jdbc:postgresql://localhost:55432/kbo`

- Do not collect data when there are no games.
- On game day before first pitch, collect data **every 30 minutes**.
- During live games, collect data **every 10 to 15 seconds**.
- Immediately after a game ends, collect data **every 1 minute**.
- Stop post-game polling once final data has been confirmed.
- Do not introduce hardcoded weekend game-time logic or any other fake time rules.
- Never trust external responses blindly; always normalize them.
- Preserve the last known good data when collection fails.
- Do not claim completion unless you clearly provide build/validation results.
- Do not say something was validated if it was not actually validated.

---

## 3. Project Working Principles
- Read the current codebase first and identify:
    - package structure
    - existing entities/repositories
    - migration tooling
    - scheduling approach
    - HTTP client approach
    - retry/locking/cache patterns
- Do not create duplicate structures for responsibilities that already exist.
- If an entity/service/model/DTO/repository already exists for the same responsibility, extend or reuse it.
- Avoid unnecessary renaming, moving files, or large refactors.
- Preserve the current naming, layering, and style unless they are clearly broken.
- Do not replace the architecture unless there is strong evidence it is necessary.
- Treat the database primary key and the app-facing `gameId` as separate concerns if the schema uses UUIDs internally.

---

## 4. Technology Standards
Prefer the following unless there is a strong reason not to:
- **Java + Spring Boot**
- Spring Web
- Spring Data JPA
- PostgreSQL
- Redis if needed later
- JSoup or similarly lightweight parser
- Browser automation tools like Playwright/Selenium only as a last resort

Do not add unnecessary technologies.

---

## 5. Recommended Package Structure
Stay close to this structure unless the existing project already uses an equivalent layout:

- `config` : datasource, scheduler, HTTP client, cache configuration
- `domain` : entities, enums, value objects
- `repository` : DB access
- `crawler` : external KBO requests
- `parser` : HTML/response parsing
- `service` : normalization, upsert, orchestration, read logic
- `scheduler` : polling control
- `api` : REST controllers
- `support` : shared utils, exceptions, time helpers, locks, hashing

---

## 6. Data Source Handling Rules
- Treat external KBO data as two layers:
    - **raw source data**
    - **normalized application data**
- If possible, preserve raw responses or at least hashes/metadata for traceability.
- Parsing failures must be diagnosable.
- Repeated identical data should not trigger unnecessary DB updates.
- Normalize game IDs, team names, statuses, times, and stadium values consistently.
- Missing fields from the external source should not automatically fail the entire job if partial success is possible.

---

## 7. Collection Policy

### 7.1 No Games
- Do not collect.
- The scheduler should exit quickly.

### 7.2 Game Day, Before First Pitch
- Poll every **30 minutes**.
- Target:
    - schedule
    - game time changes
    - cancellations/postponements
    - stadium updates
    - preview/probable starter data if available

### 7.3 Live Games
- Poll every **10 to 15 seconds**.
- Prioritize **only live games**, not all games for the date.
- Prioritize:
    - score
    - inning state
    - line score
    - major game events
    - live detail state

### 7.4 Immediately After Final
- Poll every **1 minute**.
- Stop polling when final data is considered complete.

Minimum completion criteria:
- game status is final
- final score exists
- line score exists

Recommended additional completion criteria:
- no meaningful data changes for 2 to 3 consecutive polls

### 7.5 Post-Game Safety Guard
- Never allow infinite polling.
- Use a hard limit, for example:
    - max 15 retries
    - or max 20 retries
- If final data is still incomplete after the limit, stop safely and expose stale state if needed.

---

## 8. Reliability Rules
- All external requests must have timeouts.
- Retries must be bounded.
- Use staged backoff or exponential backoff where appropriate.
- Prevent duplicate concurrent collection for the same `gameId`.
- External source failures must not immediately crash the internal API.
- Last known good data must remain readable.
- Support stale responses when necessary.
- Consider operational metadata such as:
    - `updatedAt`
    - `sourceUpdatedAt`
    - `isStale`

---

## 9. Database Rules
- Use the existing `kbo` PostgreSQL database.
- Check for schema/table conflicts before making changes.
- Manage schema changes through explicit migrations.
- Prefer Flyway or Liquibase unless the project already uses another established approach.
- Design for upsert where appropriate.
- Keep scheduled-game scores nullable until the source reports a real score.

Core concepts that should exist as tables or equivalent structures:
- `games`
- `teams`
- `line_scores`
- `game_snapshots`
- `game_events`
- `crawl_jobs`
- `crawl_failures`
- `crawl_raw_archive`

Do not over-normalize too early. Design tables around actual read/use cases and operational needs.

---

## 10. API Rules
The read API contract must be internal and normalized. Do not expose raw provider field names directly.

Examples:
- `GET /api/v1/games?date=YYYY-MM-DD`
- `GET /api/v1/games/month?year=YYYY&month=MM`
- `GET /api/v1/games/{gameId}`
- `GET /api/v1/games/{gameId}/linescore`
- `GET /api/v1/games/{gameId}/events`
- `GET /api/v1/scoreboard`

Rules:
- Responses must be app-friendly.
- Nullability must be explicit and safe.
- Separate app-facing endpoints from internal operational endpoints.

---

## 11. Delivery Order
Implement work in phases.

### Phase 1
- Inspect current project structure
- Confirm datasource/application configuration
- Confirm entity/migration approach
- Implement or stabilize daily game list collection
- Upsert games
- Deliver schedule/home read APIs

### Phase 2
- Implement game detail collection
- Persist line score
- Implement state-driven polling
- Implement live polling
- Implement post-final polling with automatic stop

### Phase 3
- Expand events/review/preview support
- Add raw archive
- Strengthen retry/backoff/locking
- Introduce Redis cache if needed

### Phase 4
- Add operational metrics
- Add health checks
- Add manual re-collect/admin endpoints
- Add alerting

Do not try to build everything at once if the current task only requires a smaller safe slice.

---

## 12. Coding Rules
- No meaningless abstractions.
- No giant God classes.
- Keep methods short and explicit.
- Use enums for finite states instead of loose string proliferation.
- Handle timezones explicitly.
- Centralize date/time formatting logic.
- Use structured logging where practical.
- Never hardcode secrets.
- Keep configuration externalized.
- Write code in a way that remains testable.

---

## 13. Validation Rules
Prioritize validating:
- no-game-day behavior
- pregame 30-minute polling behavior
- live-game polling only targeting live games
- post-final polling stopping correctly
- reduced unnecessary updates on identical data
- graceful handling of partially missing source fields
- traceable parser failures
- successful clean build

If validation is incomplete, say so clearly.

---

## 14. Operational Rules
- The app must not become blank just because collection temporarily fails.
- Prefer serving the last good snapshot when needed.
- Minimize calls to the external source.
- Do not let multiple internal endpoints independently re-fetch the same external data.
- Keep the parser/crawler/storage/api boundaries maintainable.
- Robots.txt, terms of use, and allowed access boundaries must be treated as pre-production review items.

---

## 15. Handoff Documentation Rules
For **every task**, you must leave a clear handoff record.

This is mandatory.

For each completed or partially completed task, write an **implementation handoff note** that includes:

### Required contents
1. **What was done**
    - exact files changed
    - exact behavior added, removed, or modified
    - any DB/schema/config changes
    - any commands run

2. **Why it was done**
    - the user/business requirement being addressed
    - the root cause or technical reason
    - why this implementation approach was chosen instead of other options

3. **Completion status**
    - fully completed
    - partially completed
    - blocked
    - not validated yet

4. **Validation status**
    - what was built/tested/verified
    - what was not verified
    - whether runtime/manual validation is still needed

5. **Known risks / follow-up**
    - remaining edge cases
    - technical debt intentionally left in place
    - dependencies on external source behavior
    - next recommended step

### Handoff quality rules
- Do not write vague handoff notes.
- Do not say “done” without specifying what is actually complete.
- Do not omit unfinished work.
- Do not omit failed validation.
- Write the handoff so that another engineer can continue immediately without re-discovering the context.

### Handoff frequency
- Create or update the handoff note **after every meaningful task**.
- If a task is split across multiple steps, keep the handoff note current.
- If the work is partial, the handoff note must say exactly what remains.

---

## 16. Response Format Rules
Whenever reporting implementation work, use the structure below.

### 1. Current diagnosis
- current project structure
- existing components found
- confirmed constraints
- clear root cause
- explicitly label uncertain areas

### 2. Changes made
- file-by-file summary
- why each change was needed
- how existing structures were reused

### 3. Validation
- exact commands run
- build/test results
- what could not be verified manually

### 4. Risks
- remaining risks
- pre-production checks still required
- follow-up items

### 5. Handoff note
- what was done
- why it was done
- completion status
- validation status
- follow-up needed

Do not claim validation that did not happen.

---

## 17. Prohibited Behaviors
- Creating a separate new project for the same work
- Adding hardcoded game-time rules
- Failing the entire service immediately when an external response fails
- Guess-based implementation without evidence
- Unnecessary large-scale refactors
- Claiming completion before the requested work is actually done
- Claiming build/test success without real results
- Creating duplicate source-of-truth models or duplicate time fields when one already exists

---

## 18. Final Objective
This is not just a crawler.

The real objective is to build an **operable, resilient data collection and API system** that:
- collects KBO data reliably,
- normalizes it,
- stores it,
- and exposes a stable contract that the app can trust.

All implementation decisions must support that goal.
