# Arrow Binge backend — whole-service engineering rules

These rules cover every API, service, schema, test and deployment change. They supplement higher-priority instructions.

1. Use authenticated principal ownership everywhere; validate cross-user session revoke, export, save and event actions. Never authorize through nickname or supplied owner ID.
2. Preserve challenge nonce/audience/expiry checks, refresh rotation/reuse handling and recent-auth gates. Keep secrets out of source defaults, logs, fixtures and documentation.
3. Keep stable catalogue/arrow IDs and Android schedule parity. Content revisions need compatibility handling; changing a board must not invalidate issued tickets silently.
4. Keep regular progress, event points, ranking enrollment and reward-related schema conceptually separate. Do not imply unsupported server reward verification.
5. Save/reset/import and event credit retain epoch, uniqueness and idempotency invariants. Rank is derived from authoritative records, never a client animation or arbitrary local increment.
6. Preserve nickname validation, tie semantics and own-rank behavior outside the top list. Public reads must not create artificial participants or mutate competition state.
7. New schema changes use additive Flyway migrations; never edit an applied migration. Verify upgrades, constraints and indexes on isolated PostgreSQL, not only H2.
8. Use typed validation/errors and bounded request/results. Avoid per-row SQL when batching preserves behavior. Measure execution plans and lock waits before adding caches or indexes.
9. Use deterministic transaction lock ordering and actual Spring proxy transaction boundaries. External identity HTTP must not hold database locks unnecessarily.
10. Keep body/rate/pool/statement limits explicit; validate trusted-proxy interpretation. Cleanup requires authorization, bounded batches and multi-instance coordination.
11. Test export/deletion across every domain and define receipt/backup retention. Operational diagnostics expose aggregates and correlation IDs, not personal save/token bodies.
12. Container build success is not test success. Run required tests before promotion; validate memory headroom, readiness, migrations, backup restore and rollback.
13. Do not test against production by default. Preserve user edits and existing data; describe proposed versus implemented APIs precisely in docs.
14. Optimize only with representative evidence and correctness parity. Track latency distributions, SQL count, RSS/heap, GC and contention rather than just lines of code.

The additional progress/event rules below retain the detailed recovery constraints.

## Detailed progress and event reliability workstream

Retained subsystem specification. Proposed v2 behavior is not implemented merely by documenting it; historical audit results are not a new test run.

Project guidance for the planned work, subordinate to explicit user instructions and tool policies.

1. Define behavior through PRD.md and canonical design.md; existing broken behavior is not a compatibility requirement except where old clients need a tested adapter.
2. Use isolated local PostgreSQL or disposable test infrastructure. Never run TRUNCATE, synthetic loads or destructive integration tests against hosted player data.
3. Preserve epoch, account isolation, run identity and idempotency. Do not trade correctness for shorter transactions by splitting atomic credit/receipt writes.
4. Every success acknowledgement corresponds to committed state and identifies exactly what it covers. Lost responses are recovered by the same immutable operation/ticket.
5. Validate bounded typed input before expensive work. Nulls and invalid arrays produce stable 4xx errors, not unhandled exceptions.
6. No network/JWK fetch while holding progress locks. Keep Google verification outside the final short nonce-consumption transaction with a recheck.
7. Declare transaction boundaries at actual proxied entry points; do not assume self-invoked annotations take effect.
8. Parameterized SQL only; batch repetitive writes and avoid unchanged updates. Explain/measure new indexes on realistic synthetic data.
9. Read APIs must not create fake users, enroll players, award points or initialize tournaments implicitly.
10. No fabricated ranks/points. Rank movement is receipt-derived and can legitimately be zero.
11. Server-issued ticket expiry/content must match returned values on every retry. Completed-ticket replay remains successful without awarding again.
12. Fail closed for missing required auth/cleanup secrets. Trust forwarded client addresses only through verified ingress.
13. Bound request bodies, responses, workers, caches and cleanup. Never delete idempotency receipts without a safe replay-horizon/tombstone contract.
14. Add migrations; never modify already applied V1–V3. Preserve v1 behavior through adapters while introducing v2 capabilities.
15. Do not expose tokens, credentials, raw Google claims, full save bodies or player email in logs/docs/test artifacts.
16. Do not “repair” the failing rank test by accepting synthetic-user ranks without deciding product behavior.
17. Unit/integration results do not certify production throughput. Report environment, payload size, concurrency, latency and memory with benchmarks.
18. Update both repositories’ TASKS.md and memory.md after verified milestones. Keep planning tasks distinct from completed runtime work.
