# Arrow Binge backend — durable whole-service context

Updated October 21, 2026. Records source facts, decisions and validation limits for the entire backend.

## Source baseline

- Sibling of the Android repository; inspected branch main. Java 17, Spring Boot 4.1.1, MVC/Security/JDBC/Flyway/PostgreSQL; single application deployment.
- APIs cover Google challenges/login, refresh/logout, profile/session management, export/delete, regular save/reset, regular rankings and daily/weekly events. Actual controllers use v1; v2 below is proposed.
- Schema migrations V1–V3 cover account/save/reward-related tables, regular leaderboard and event credit. Reward-wallet tables do not prove a live client reward integration.
- Event schedule is UTC, Monday week boundary, 50 tournament boards selected from IDs 1–50. Android calendar/catalogue parity is a release invariant.
- Docker builds skip tests and uses SerialGC/75% RAM heap sizing; record performance evidence before changing/treating as optimal. Deployment secrets must not be reproduced here.
- The six backend documents remain in the repository root. Android counterparts now live in ../arrow-binge/docs/ with uppercase names.

## Evidence discipline and known concerns

Prior isolated PostgreSQL audit recorded 34 tests with one synthetic-competitor ranking failure, query-plan probes and flow probes. Those observations are historical, not a fresh full-suite run or deployed-service diagnosis. This turn revisits controllers, schedule, ranking, schema, build and service boundaries; it changes documentation only.

Known workstreams include auth transaction duration, save batching/acknowledgements, event receipt/expiry, synthetic competitors, bounded history/attempt reads, rate-limit costs, cleanup authorization/retention and JVM/database capacity. See performance-flow-audit.md under docs for evidence and TASKS for acceptance. Production configuration, traffic, live database state and backup recovery have not been certified.

## Product boundaries and continuation

Backend owns identity, durable cloud data and competitive credit. Android owns rendering/local game input and SDK ad-earned behavior. Keep supported-client compatibility, stable catalogue IDs and replay protection. Do not introduce a new wallet, real-time protocol or content service implicitly during reliability work. Update this file after verified milestones, noting command/environment and limitations.

## Detailed progress and event reliability workstream

Retained subsystem specification. Proposed v2 behavior is not implemented merely by documenting it; historical audit results are not a new test run.

Updated October 21, 2026. Durable context for future implementation.

### Location and baseline

Repository is the sibling `arrow-binge-backend`, not the old Downloads location. Inspected branch `main`, HEAD `b0e8bc8` (audit documentation commit); runtime findings from `f73e355` remain present. Frontend is `../arrow-binge`, branch `codex/backend-cloud-save`, with substantial ongoing work.

Java 17, Spring Boot 4.1.1, JdbcTemplate, PostgreSQL 17, Flyway V1–V3. Default Hikari maximum is five. Existing schema contains owner progress, attempts, operation receipts, guest imports, sessions, events and reserved wallet tables. Wallet/AdMob server-side verification is not implemented.

### Verified audit evidence

October 20 isolated local PostgreSQL suite: 34 tests, 33 pass, one rank test fails (expected 1, observed 7) because read paths seed synthetic contenders. Service probes verified a reused ticket expiry mismatch, null-unlock NullPointerException, blank-secret unauthenticated cleanup, and 30 synthetic users created by public schedule.

Synthetic SQL experiment: 100,000 attempts, 1,000 open; candidate partial index changed open-attempt query from 5.241 ms to 0.406 ms in one local run. These are not production latency figures. Evidence is in [docs/audit-evidence](docs/audit-evidence). Temporary PostgreSQL was stopped after the audit; hosted data was not touched.

### Current vs target

Current finish returns only accepted/points; it has no durable rank-before/rank-after receipt exposed to Android. Current sync returns a full save, not the proposed explicit v2 ack. Existing JWT verification can hold a database transaction; save writes are per row. Active-attempt responses and history retention need bounds. Current public schedule has write side effects.

The v2 protocol, service boundaries and tasks in these new root documents are proposed, not implemented. Do not tell users the flow is fixed because these files exist.

### Decisions

Keep account locks, idempotent retries, monotonic stars/unlocks and reset epochs. Replace ambiguous outcomes and coupled read/write flows. Require receipt-based tournament presentation; do not promise every win changes rank. Default plan removes synthetic competitors from real rankings, while preserving real player progress; existing seeded rows need an explicit migration decision. Use additive migrations and keep v1 compatibility during rollout.

### Resource constraints

Bound body bytes, page size, active runs, request concurrency, pool wait and cleanup batches. Keep enough native memory outside heap. Do not add unbounded rank caches or enlarge the pool without measuring. Receipt retention and tombstones must preserve replay safety; ordinary cache eviction must never delete authoritative credit history.

### Still to verify

Actual deployed commit and configuration, trusted-proxy setup, external cleanup schedule, staging OAuth, real database latency/capacity, intended synthetic-opponent policy, privacy-compatible import tombstones after deletion, restored daily history, JVM/native memory and GC behavior. No live-service assumptions should replace these checks.

Read [PRD.md](PRD.md), [architecture.md](architecture.md), canonical [design.md](design.md), [RULES.md](RULES.md), [TASKS.md](TASKS.md), and the [frontend recovery plan](../arrow-binge/docs/cloud-save-recovery-plan.md). Store no credentials or player data in documentation.
