# Arrow Binge backend — complete service backlog

Updated October 21, 2026. Scope includes all services and operations, not only cloud save. Existing BE identifiers below retain the detailed recovery workstream. P0 denotes data/security/correctness; P1 denotes required service/release reliability; P2 denotes evidence-driven optimization.

| ID | Priority | Deliverable | Acceptance |
|---|---|---|---|
| SYS-01 | P0 | Full route/auth matrix and secret configuration audit | All public/protected/admin routes tested; no secret fallback; wrong-owner/revoked-session access rejected |
| SYS-02 | P0 | Identity/session concurrency tests | Nonce reuse, invalid audience, expiry, refresh replay/reuse, logout/revoke and Google timeout handling |
| SYS-03 | P0 | Regular progress workstream | BE-03/04 below; null/malformed input, concurrent reset/sync, imports and lost-response replay |
| SYS-04 | P0 | Event workstream | BE-05/06/07; schedule parity, original expiry, final board, duplicate finish and receipt recovery |
| SYS-05 | P1 | Regular leaderboard audit | Join/rename/leave, tied ranks, own row beyond top 50, score update/reset consistency and population query cost |
| SYS-06 | P0 | Privacy/export/delete lifecycle | Recent auth and owner checks; complete domain export; cascade/retention policy and isolated backup restore |
| SYS-07 | P1 | Catalogue compatibility | Supported Android/server catalogue fixtures match; invalid IDs rejected; versioned changes preserve active tickets |
| SYS-08 | P1 | Reward-schema scope audit | Identify used/unused wallet/reward tables; document actual API coverage; no destructive removal without migration review |
| SYS-09 | P0 | Abuse/cleanup protection | Endpoint-aware limits, bounded bodies, proxy validation, protected cleanup and concurrent-job safety |
| SYS-10 | P1 | Database performance/retention | BE-09/13; measured indexes, batched writes, bounded history/attempts and replay-safe cleanup |
| SYS-11 | P1 | Deployment/observability/runbook | Required config, readiness, migration order, pool deadlines, error/latency metrics, backup/restore and data-preserving rollback |
| SYS-12 | P1 | Release validation pipeline | Tests before image promotion; real PostgreSQL schema upgrade; mixed client versions and staging Google/device flows |
| SYS-13 | P2 | Maintainability | Typed DTO/repository seams, readable service formatting, eliminate proven duplication without protocol changes |

- [x] Expand all six documents to full-service scope with actual v1 inventory and separate proposed v2 design.
- [ ] Complete SYS-01–13 with recorded evidence; documents alone do not certify the backend.

Order: route/secret/data invariants → contract and regressions → durable save/event changes → privacy/rank/catalogue coverage → capacity and operational hardening → staging/release. Coordinate with Android APP/FE tasks. Do not replace the working JDBC architecture wholesale just to shorten code.

## Detailed progress and event reliability workstream

Retained subsystem specification. Proposed v2 behavior is not implemented merely by documenting it; historical audit results are not a new test run.

October 21, 2026. Planning complete; runtime changes pending. FE references point to [Android TASKS.md](../arrow-binge/docs/TASKS.md).

### Contract and baseline

- [x] Review save/auth/events implementation and preserve prior audit/test evidence.
- [x] Define requirements, target architecture, canonical protocol and implementation rules.
- [ ] BE-00: Confirm deployed commit/configuration and reproduce current failures on staging. Identify seeded contenders separately from real users.
- [ ] BE-01: Produce versioned OpenAPI/schema and shared fixtures for bootstrap, ack, receipt, cursor and errors; agree replay and preference-conflict behavior with FE-05/09.
- [ ] BE-02: Add failing regression tests for ticket expiry, null unlocks, no-secret cleanup, private read/write coupling, rank seeding and receipt replay.

### Progress and account lifecycle

- [ ] BE-03: Add durable v2 sync acknowledgement fields/migrations and batch repositories; unchanged-row suppression, deterministic payload hash, revision/epoch checks and replay lookup. Preserve v1 adapter behavior.
- [ ] BE-04: Implement bounded bootstrap, attempt pages, guest import recovery and preference conflict semantics. No standings dependency or mutation on read. Test migration/import under concurrent reset and process retry.
- [ ] BE-08: Shorten Google login transaction with nonce recheck; require proper secret configuration; idempotent revocation and bounded external timeouts. Test refresh rotation/reuse and cancellation races.

### Tournament correctness

- [ ] BE-05: Persist immutable event completion receipt, per-player revision and rank observations with unique credit in one transaction. Same-ticket retry returns original receipt; different-ticket duplicate returns explicit no-additional-credit outcome.
- [ ] BE-06: Store ticket content version and original expiry; enforce next-board prerequisite and final-board behavior. Finish accepted before deadline remains replayable after deadline; new late finish is terminal.
- [ ] BE-07: Remove production read-triggered synthetic seeding; decide existing synthetic-row migration without touching real scores. Side-effect-free rankings and paginated daily history; correct same-rank and outside-top-50 behavior.

### Resource bounds and operations

- [ ] BE-09: Add measured partial open-attempt index; bound active-run/history responses and implement safe receipt/tombstone retention. Review cleanup indexes, batch sizes and single-owner scheduling.
- [ ] BE-10: Endpoint-specific body limits, efficient bounded stream, atomic limiter increment/return, separate auth/read budgets and Retry-After. Verify trusted-proxy handling.
- [ ] BE-11: Configure and test request/pool/statement/lock deadlines, readiness, required secrets and protected cleanup. Measure JVM/native headroom and multi-instance connection budget.

### Validation and rollout

- [ ] BE-12: Run full isolated PostgreSQL suite; fix the known ranking failure according to agreed behavior. Add concurrent duplicate finish/save/reset tests, malformed input, >100-day restore and old-schema upgrade tests.
- [ ] BE-13: Run load profiles for small/max sync, abandoned attempts, shared-IP polling, cold Google key retrieval, leaderboard population and concurrent cleanup. Record p50/p95/p99, SQL count, lock/pool wait, bytes, heap/RSS and GC.
- [ ] BE-14: Stage with FE-13–15: two-device cloud restore, offline replay, logout barriers, real Google OAuth, tournament receipts and no fake rank movement. Verify old-app/new-server and new-app/old-server combinations.
- [ ] BE-15: Roll out compatible v2 with monitoring and a data-preserving rollback. Record deployment evidence in memory.md; do not retire v1 before supported clients migrate.

### Dependency order

BE-01/02 → BE-03/04 and BE-05/06 → FE account/result integration → BE-12/14. BE-07/08/09/10/11 may proceed within their documented boundaries, but completion correctness precedes caching/rank acceleration. BE-13 validates performance claims after correctness passes.

### Mandatory release assertions

- No duplicate points, reopened closed attempts, cross-account writes or stale-epoch resurrection.
- Every accepted finish has a recoverable receipt. Never advertise a stored ticket as valid longer than it is.
- Public schedule/read calls write no player data.
- Stars completed on device A restore on device B; unsent local progress survives account transitions.
- Logout progress UI is immediate and backend failure is distinguishable from local-only logout.
- Unknown rank/score remains unknown; unchanged rank is correctly displayed, and receipt replay does not roll back newer totals.
- Full existing suite passes, mixed-version deployment is checked, and measured resource budgets are documented.
