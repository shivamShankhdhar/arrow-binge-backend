# Arrow Binge backend — complete service requirements

Updated October 21, 2026. Scope: the entire Spring Boot service, its PostgreSQL data and operational lifecycle. Current v1 behavior is distinguished from proposed reliability contracts below.

## Product purpose and responsibility

Support the Android offline puzzle game with authenticated recovery, cross-device saves, optional public rankings and daily/weekly event credit. Regular rendering, collision rules, local hints, AdMob and Play updates remain client responsibilities. The server must never trust a supplied user ID in place of the authenticated principal.

## Complete service scope

| Domain | Current surface | Required acceptance |
|---|---|---|
| Identity | Google configuration/challenge/login; device sessions; refresh/logout | Valid audience/nonce/expiry; single-use challenges; refresh replay/reuse rules; revocation and bounded external verification |
| Profile/privacy | Profile, session list/revoke, account export/delete | Owner authorization, recent-auth gates where required, complete bounded export, reliable deletion and documented retention |
| Regular progress | Read/sync/reset; stars/unlocks/attempts/preferences | Atomic epoch-aware merge; closed attempts stay closed; retries cannot duplicate effects; no cross-owner writes |
| Legacy import | Migration/import persistence and save handling | Stable import identity; no replay into another owner; preserve existing supported-client semantics |
| Regular leaderboard | Nickname enrollment/leave, top 50 and own rank | Scores derived from persisted progress, deterministic ties/order, own rank beyond top 50, explicit opt-in |
| Daily challenge | UTC schedule, run ticket, completion and history | Client/server rotation parity; correct stored expiry; unique daily credit; complete history restoration |
| Weekly tournament | Monday UTC, 50 rotated boards, enrollment/leave, standings and finish | Valid content/pulled IDs and run ownership; unique board credit; correct totals, deadlines and honest rankings |
| Catalogue | Packaged catalog.json and Catalog validation | Same stable level/arrow identity as supported client builds; reject invalid IDs and malformed completion payloads |
| Abuse protection | Security filter, request body bound, rate buckets | Enforce authentication and resource limits consistently; bounded error shape; shared-IP users not blocked by unrelated polling |
| Operations | Flyway migrations, cleanup, health/root behavior, Docker | Repeatable deploy/upgrade; protected cleanup; readiness reflects dependencies; backups and restore tested before release |

Schema includes hint wallets, allocations and reward-intent/receipt tables; their existence does not establish a live, verified ad-reward service. Do not advertise synchronized hint economy or ad verification without explicit APIs and end-to-end validation.

## Quality and release acceptance

Correctness precedes throughput: lost responses, duplicated requests, simultaneous reset/sync, enrollment/finish races and week rollover must preserve invariants. Bound bodies, query results, transaction/pool waits and cleanup batches. Measure SQL count, p95/p99 latency, lock contention, heap/RSS and GC at stated population/concurrency; targets are not measured guarantees.

Required release evidence: isolated PostgreSQL integration suite, Flyway upgrade from an existing schema, supported old/new client compatibility, staging Google authentication and two-device restore, event replay/deadline checks, deployment rollback and database recovery procedure. Avoid production test writes. No cash prizes or real-time multiplayer are in the verified product scope.

Read [architecture](architecture.md), [design](design.md), [memory](memory.md), [rules](RULES.md), [tasks](TASKS.md) and the [Android PRD](../arrow-binge/docs/PRD.md).

## Detailed progress and event reliability workstream

Retained subsystem specification. Proposed v2 behavior is not implemented merely by documenting it; historical audit results are not a new test run.

Status: proposed requirements, October 21, 2026. No runtime change is implied by these documents.

### Goal

Provide an explicit, bounded and replayable contract for account reconciliation, regular progress, logout acknowledgement and tournament results. A successful response must correspond to committed server state. Clients must be able to distinguish missing data, pending work, duplicates and terminal rejection.

| ID | Requirement | Acceptance |
|---|---|---|
| B1 | Immediate authenticated bootstrap | Return account/save metadata and bounded progress without requiring public events or leaderboard initialization. |
| B2 | Safe same-account synchronization | Preserve best stars/unlocks, reset epochs, run identity and idempotent retries; reject a reused operation ID with different data. |
| B3 | Explicit acknowledgement | Identify owner, epoch, operation and captured client revision in a committed receipt. The client can tell which local work is covered. |
| B4 | Once-only guest import | Transactional import claim; retry recovers original result, different owner receives explicit conflict. |
| B5 | Durable event receipt | Unique completion credits once; retries return the original receipt with authoritative points, total, player revision and rank observation. |
| B6 | Accurate ticket lifecycle | Return stored expiry on reuse, persist content version, and issue next playable board only after prerequisite completion. |
| B7 | Honest ranking | No synthetic accounts masquerading as real players; no guaranteed rank rise from a completion; ties use documented competition ranking. |
| B8 | Bounded resource use | Batch writes, cap bodies/pages/concurrency, bound active-run growth and define retention without invalidating offline replay correctness. |
| B9 | Operable deployment | Fail closed for missing required secrets, authenticated bounded cleanup, readiness distinct from liveness, observable failures. |
| B10 | Compatible rollout | Additive/versioned v2 endpoints coexist with v1 until supported clients migrate. |

### Domain policy

- Regular stars/unlocks are local-first, monotonic within an epoch. A remote reset increments epoch and invalidates older writes.
- Preferences use declared revision/precondition conflict rules; late offline uploads cannot silently overwrite newer preferences.
- Tournament points equal the catalog arrow count for a newly credited unique player/week/board. A board already credited yields no additional points and returns a clear duplicate outcome.
- A retry of the same successful ticket returns the same original receipt; it must not credit again or be rejected solely because the response was lost and the week later ended.
- Tournament submissions first received after the deadline are not awarded. Clients get an explicit terminal outcome. This release does not introduce offline deadline grace or prize-grade anti-cheat.
- Daily completion history is recoverable beyond the last 100 entries through pagination.
- No new cloud wallet/reward semantics in this work.

### Success criteria

Pass all existing tests after resolving the synthetic-opponent behavior, add receipt/replay/migration/concurrency tests, and pass the shared frontend acceptance matrix. Use isolated PostgreSQL for destructive tests and staging for actual Google OAuth. Load-test with realistic database latency before claiming capacity.

Initial service target: under the declared staging load profile, normal bootstrap/small-sync/event-finish p95 below 2 seconds, with endpoint deadlines and explicit errors. This is a proposed SLO, not a measured guarantee; record request sizes, concurrency, network latency and container limits alongside measurements.

See [architecture.md](architecture.md), canonical protocol in [design.md](design.md), [TASKS.md](TASKS.md), and [Android PRD](../arrow-binge/docs/PRD.md).
