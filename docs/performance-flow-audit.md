# Backend performance, memory, and flow audit

October 20, 2026. Reviewed checkout: `f73e355` in the sibling `arrow-binge-backend` repository.

## Conclusion

The backend already has useful correctness foundations: parameterized SQL, per-account transactional writes, idempotent operation IDs, reset epochs, indexed token lookups, and database constraints. Keep them.

The highest-value changes are batching sync writes, bounding save history and request concurrency, shortening transactions, separating reads from tournament initialization, and fixing the verified flow defects below. A framework rewrite or cache added in front of every query would introduce complexity without addressing these causes.

This is an analysis deliverable. No production Java, SQL migrations, or deployment settings were changed.

## Scope and verification

Reviewed all production Java services/controllers/helpers, the three database migrations, application configuration, both Dockerfiles, deployment configuration, and tests. Traced sign-in → restore → sync → completion → rankings → logout/reset/delete, plus daily/tournament ticket creation and completion.

Created a disposable PostgreSQL 17 cluster bound to loopback on a separate port. Tests used explicit local database settings with inherited database URL/credential variables removed. No hosted database was queried.

- Maven suite: **34 tests, 33 passed, 1 failed, 0 errors, 0 skipped**.
- Failure: `EventIntegrationTest.enrollmentAndDatesAreValidatedAndTiesShareRank`, line 75, expected rank 1 but received 7.
- Standalone service probes reproduced expiry mismatch, null-unlock failure, unauthenticated cleanup under default configuration, and synthetic-user creation on schedule read. These probes invoked compiled service objects directly; they were not concurrent HTTP load tests.
- Synthetic query-plan experiment: 100,000 leaderboard rows and 100,000 attempt rows, with 1,000 attempts open. Inserts and candidate index were rolled back.
- Evidence: `audit-evidence/test-summary.txt`, `flow-probes.txt`, `query-probe.sql`, and `query-plans.txt`.
- No live OAuth, production load test, JVM allocation recording, heap dump, or deployment-state verification was performed. Configuration risks are conditional on the deployed overrides and ingress.

## Priority findings

### 1. P1 — A large save executes thousands of statements under the account lock

**Location:** `SaveService.java:25–52`.

The request allows 1,000 levels, 1,000 unlocks, and 2,000 attempts. Each is written using a separate `db.update`, after acquiring the user row with `FOR UPDATE`. A maximum-size request can issue roughly 4,000 item-level statements plus bookkeeping, score aggregation, and the full save read. The default pool contains five connections, so several slow transactions can exhaust it.

**Improvement:**

1. Validate and prepare bounded payload data before starting the database transaction where possible.
2. Batch compatible inserts/upserts using `JdbcTemplate.batchUpdate` or bounded set-based statements.
3. Keep epoch check, deduplication, progress updates, and operation receipt atomic under the same user lock.
4. Add a conflict-update condition so unchanged/lower star values do not rewrite rows. Preserve `GREATEST` semantics.
5. Keep full snapshots initially; introduce changed-level/attempt deltas only with a versioned client/server contract.

Do not release the account lock halfway through an operation merely to reduce latency. Benchmark actual driver batching; one Java batch does not automatically mean one database round trip.

### 2. P1 — Save responses and historical storage can grow without a bound

**Location:** `SaveService.java:43,50,59`; `ExpiredDataCleanup.java`.

Request size is bounded, but `read()` returns every open attempt across every device/run. A client can accumulate new run IDs over time; older abandoned runs do not necessarily close. Closed attempts and `sync_operations` are never cleaned up. The 2,000-attempt request cap does not bound the account's stored attempts or response size.

**Improvement:** Define active-run limits and pagination/cursors, archive obsolete runs, and retain closure tombstones needed to reject stale offline writes. Bound response bytes as well as row counts. Define an operation-replay retention contract before expiring deduplication receipts: naive deletion would permit old operations to be applied again.

Session listing and account export also materialize full result lists. Add bounded/paginated sessions and a consistent, streaming or staged export for large histories.

### 3. P1 — Default cleanup endpoint permits unauthenticated expensive work

**Location:** `RootController.java:35–44`; `SecurityConfiguration.java`.

When `app.cron-secret` is blank, `/internal/cleanup` skips authorization and executes all cleanup queries. It is outside the `/v1/` rate-limiting branch. A local probe with no authorization successfully deleted an expired challenge.

**Improvement:** Disable the endpoint or reject requests when the secret is missing. Require authenticated invocation, prevent overlapping cleanup executions, and use a mutation-appropriate method or the deployment scheduler's supported authenticated mechanism.

This matters for availability as well as access control: repeated full cleanup scans can consume database resources.

### 4. P1 — Public schedule reads create synthetic competitors and break ranking expectations

**Location:** `EventService.java:17,97–139,145,157`.

`GET /v1/events` invokes contender initialization. When fewer than ten tournament entries exist, it attempts 30 user inserts and 30 tournament-player inserts. Those entries have fixed scores and appear as ordinary participants. A fresh local probe created 30 synthetic users. The existing real-player rank test now fails because these entries outrank the tied players.

**Improvement:** Make an explicit product decision about synthetic opponents. Prefer removing production seeding and retaining it only in test/demo fixtures. If required, identify and separate synthetic opponents from real rankings and participation counts. Initialize once through a migration/job or unique weekly marker; do not seed from GET paths or use participant count as an initialization flag.

Do not “fix” the regression by changing the expected rank to 7 before deciding the intended behavior.

### 5. P1 — External Google key retrieval can hold a database transaction open

**Location:** `AuthService.java:11–15`; `GoogleIdentity.java`.

Login acquires a challenge row lock and a database connection before verifying the JWT. Verification may fetch Google signing keys on a cache miss. Network delay can therefore consume the small connection pool and block another attempt using that challenge.

**Improvement:** Read the challenge, verify the JWT with bounded external timeouts, then enter a short transaction that rechecks expiry/consumption and atomically consumes the challenge before creating the session. Reject if another request consumed it. Preserve single-use nonce semantics; do not consume the nonce permanently before verification succeeds.

### 6. P2 — Request buffering multiplies heap use under concurrency

**Location:** `BoundedRequest.java:7–22`; Dockerfiles.

Every accepted request is fully buffered up to 4 MiB before JSON deserialization. The raw bytes coexist with deserialized collections, validation structures, serialized hashing input, and response objects. For example, 50 simultaneous maximum bodies alone account for approximately 200 MiB, before those additional allocations.

The wrapper overrides single-byte `read()` but not bulk `read(byte[], off, len)`; the inherited implementation performs per-byte reads. This is avoidable overhead when Jackson reads buffers.

**Improvement:** Use a counting/limited stream that enforces the limit while parsing, or retain buffering only where replay is required and implement bulk delegation correctly. Use smaller endpoint-specific limits for login, refresh, enrollment, and completion. Configure bounded request concurrency and ingress/body-read timeouts. Keep chunked-body enforcement.

This is an allocation/concurrency risk, not a measured production leak.

### 7. P2 — Open-attempt queries lack an index matching filter and order

**Location:** `SaveService.java:59`; `V1__accounts_and_saves.sql`.

The primary key starts with user ID, but does not match `closed=false ORDER BY updated_at DESC`. A suitable candidate is:

```sql
CREATE INDEX level_attempts_open_by_user_updated
ON level_attempts(user_id, updated_at DESC)
WHERE closed=false;
```

In the synthetic experiment, the current query scanned 100,000 rows, rejected 99,000 closed rows, and sorted 1,000 rows. The candidate used an ordered index scan.

| Local probe | Execution time |
|---|---:|
| Existing open-attempt query | 5.241 ms |
| With candidate partial index | 0.406 ms |
| Indexed leaderboard top 50 | 0.035 ms |
| Count players above a low score | 7.455 ms |
| Count all leaderboard participants | 5.444 ms |

These are single warm local measurements using small placeholder attempt JSON; they are evidence of query shape, not production latency predictions. Test with real account distributions, payloads, cache conditions, and concurrent writes before migration.

### 8. P2 — Exact rank and participant counts scale with population

**Location:** `LeaderboardService.java:43–57`; `EventService.java:148,161–163`.

The top-50 query already uses the existing score index efficiently. The separate own-rank query counts every player with a greater score; for low-ranked players it can scan most participants. Exact participant counts also scan the population. Event initialization performs another count even when no initialization is needed.

**Improvement:** Remove repeated initialization counts first. Cache shared top-50/count results briefly with bounded entries and a declared freshness policy. Keep account-specific data separate. At larger scale consider score-frequency aggregates for exact ranks, with transactional score updates or documented eventual consistency.

Do not add a new index identical to the existing score index or replace the efficient top-50 query with a full-population sort.

### 9. P2 — Rate limiting adds database writes to every request and mixes unrelated public traffic

**Location:** `SecurityConfiguration.java:39–49`.

Each request increments a SQL bucket and then reads it. Authenticated calls also query the session. Public event polling and Google authentication share one 30-per-minute IP bucket. Several players behind the same network can exhaust it through ordinary polling and then encounter sign-in failures. The two-statement increment/read can also observe another request's later count.

**Improvement:** Use `INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING requests` for one atomic database round trip. Separate auth and read-only polling budgets; return Retry-After. Verify forwarded headers are accepted only from trusted ingress, because `clientIp` directly trusts supplied forwarding headers. At higher scale, move public coarse limiting to the ingress or a shared bounded limiter with an explicit multi-instance policy.

Do not introduce an unbounded in-memory map of client IPs.

### 10. P2 — Reused daily tickets return an incorrect expiry

**Location:** `EventService.java:58–61`.

The lookup retrieves only the existing ticket ID. The response always returns a newly calculated expiry, without updating the stored ticket. A probe reused a ticket with 60 seconds left and advertised approximately 24 hours: **86,340 seconds later than its database expiry**.

**Improvement:** Select and return the stored expiry for an existing ticket. If renewal is intended, make it an explicit atomic operation with defined tournament/daily rules.

**Required regression:** Create a daily ticket, advance an injected Clock, request it again, and assert the response and database expiry agree.

### 11. P2 — Transaction annotations do not apply on internal method calls

**Location:** `EventService.schedule → ensureTournamentContenders`; `SaveService.sync/reset → read`; service `join → read/mine`.

Spring's proxy transaction interception applies at external bean calls. `schedule()` is not transactional, so its internal call to annotated `ensureTournamentContenders()` does not establish that transaction. Seeding may partially commit or interleave.

Calls from existing write transactions do remain inside those transactions, but inner read-only/repeatable-read annotations do not change isolation. Treat them as participating in the caller's transaction, not as independent snapshots.

**Improvement:** Define transaction boundaries at actual service entry points or use a separate transactional initializer/TransactionTemplate. Make event read APIs side-effect-free and explicitly choose consistent-read semantics where entries, rank, and participant count must agree.

### 12. P2 — Invalid input can escape validation as an internal error

**Location:** `SaveService.java:18`.

A valid JSON collection can include a null unlock. The enhanced loop unboxes it into `int` before validation, throwing `NullPointerException`. The local probe confirmed this; the configured exception advice does not map that exception to the intended invalid-save response.

**Improvement:** Validate nullable collection elements before unboxing and use typed validation at the request boundary. Test null entries, duplicate arrow IDs, oversized arrays, absent required fields, and malformed dates. Add explicit duplicate detection for attempt arrow IDs if the save-state contract requires set semantics.

Return a stable 400 response; do not broadly map every internal exception to 400 and hide server defects.

### 13. P2 — Full daily history is stored but only the last 100 days are restored

**Location:** `EventService.java:159`; Android `EventManager.readMine`.

The server returns `dailyDays` with `LIMIT 100`, while Android merges only the returned days. A new installation cannot restore earlier completions through this endpoint even though the server still stores them and export can include them.

**Improvement:** Add paginated history/cursor reconciliation or return a compact, bounded-by-contract complete history. Keep current standings independent of historical daily restore so frequent ranking refreshes do not repeatedly download that history.

### 14. P2 — Offline retries can overwrite newer preferences

**Location:** `SaveService.java:46`.

Stars and unlocks merge monotonically, but `user_preferences` is replaced on every accepted snapshot. A delayed older-device save can replace a more recent `lastPlayed`, sound, and haptics selection.

**Improvement:** Establish per-field conflict rules and revision/precondition semantics. Use recency metadata with a server-defined ordering policy; do not compare level IDs as timestamps. Preserve a stable operation ID for retrying the same update.

### 15. P2 — Cleanup becomes expensive and unreliable without an execution/retention policy

**Location:** `ExpiredDataCleanup.java`; `application.properties`; `vercel.json`.

Cleanup starts an hour after process startup and performs unbatched deletes. On a short-lived instance it may never run; on multiple long-lived instances they may all run it. The checked-in deployment configuration does not declare a cron schedule. Expiry predicates lack dedicated indexes in the migrations.

**Improvement:** Use one authenticated external schedule or a database-backed leader/lease. Delete bounded batches, measure candidates with EXPLAIN, and add selective expiry indexes where justified. Monitor oldest expired row, rows deleted, duration, and failures. Include revoked-session retention and the history policies in finding 2.

Do not assume the deployed host has no external scheduler; verify deployment state before changing it.

### 16. P2 — Runtime memory settings need load measurements

**Location:** `Dockerfile`, `Dockerfile.vercel`, `application.properties`.

Both images configure a heap ceiling of 75% of container memory, Serial GC, and 512 KiB thread stacks. The repository does not define servlet concurrency/queue limits or JDBC statement/lock timeouts. The remaining container memory must accommodate metaspace, code cache, stacks, direct/native buffers, and request overhead. A five-connection pool can cause request threads to queue while retaining large deserialized saves.

**Improvement:** Record actual container RSS, heap/native use, GC pauses, thread count, pool wait, and lock wait. Set bounded concurrency and timeouts together. Compare GC configurations for the target memory/CPU limits instead of declaring Serial GC universally optimal. Budget total database connections as maximum live instances × pool size.

Avoid increasing Hikari pool size before shortening transactions and measuring the database's capacity.

### 17. P2 — Configuration defaults conceal missing deployment requirements

**Location:** `application.properties`; `AuthService`; `GoogleIdentity`.

A built-in session-secret fallback passes the length-only configuration check. Google authorized-party validation also accepts certain unlisted IDs sharing the configured project prefix. These are correctness/security decisions, not performance optimizations.

**Improvement:** Require a deployment-supplied session secret and fail startup clearly when missing. Define and enforce the intended exact authorized-party policy. Avoid logging secrets. Exercise real staging OAuth and retry-token rotation after tightening configuration.

`/health` always returns UP independently of database connectivity; distinguish liveness from database readiness so traffic is not routed to an instance unable to serve saves.

## Smaller code improvements

- `SaveService.read` parses JSON into a tree and converts the tree into a Map. Deserialize once into the intended type, or return a JsonNode without a second object graph.
- `leaderboardProfile()` calls the full top-50/rank/count read to export only the user's profile. Add a direct profile query.
- Replace repeated `SELECT *` with necessary columns, particularly session authentication and existing-ticket lookup.
- Make tiny catalog compatibility maps/sets static constants instead of recreating them per attempt validation. Keep the catalog loaded once; 1,000 entries are not an excessive cache.
- Combine score aggregation into one scan where measured useful. Incremental score updates must still handle best-stars merges, duplicate replay, reset, join, and deletion correctly.
- Inject a Clock into auth/events/cleanup to make expiry and week-boundary tests deterministic.
- Format compressed one-line service methods into readable validation, transaction, query, and response steps. This improves reviewability; shorter source is not faster execution.
- Do not introduce a JPA layer solely for optimization. Explicit JDBC already makes query counts and locking visible.
- Event tickets do not persist a catalog content version. If board geometry changes while tickets are outstanding, validation uses current counts; version tickets or define upgrade invalidation.
- Account export spans several independent calls/transactions, so its components can represent different instants. Use an explicit consistent snapshot or a documented export version.

## Proposed delivery sequence

1. **Flow correctness:** resolve synthetic-opponent policy and failing test; fix cleanup authorization, ticket expiry, invalid-input handling, and required configuration.
2. **Database work:** batch saves, suppress unchanged writes, add measured open-attempt index, make reads side-effect-free, shorten login transactions.
3. **Bounded resource use:** limit active attempts/response sizes, stream/cap bodies, configure concurrency/timeouts, define retention and single-owner cleanup.
4. **Read scaling:** separate daily restore from rankings, cache shared reads only where needed, improve rank/count strategy at measured scale.
5. **Load and release validation:** assess actual container/database limits before rollout.

## Required performance and regression tests

- Small and maximum legal snapshots, both replayed and new; report SQL calls, bytes, transaction duration, lock wait, and p50/p95/p99 latency.
- Concurrent saves for one account versus different accounts; reset versus sync; refresh replay and logout during requests.
- Abandoned attempt accumulation and large exports; enforce documented response/memory bounds.
- Cold Google-key cache with slow/failing external verification; ordinary saves must remain serviceable.
- Shared-IP public polling followed by sign-in; correct throttling and Retry-After.
- 100,000+ ranking population with top, middle, and low personal ranks.
- Concurrent cleanup and gameplay writes; no overlapping unbounded cleanup jobs.
- Daily-ticket retries near expiry, weekly rollover, account deletion, stale content versions, and reinstall restoring more than 100 daily completions.
- JFR/GC/native-memory recording under bounded sustained load and oversized/chunked requests.
- Test under production-like network latency to PostgreSQL; loopback results hide the cost of thousands of statements.

Keep synthetic benchmarks isolated from hosted player data. A passing functional suite and one fast local SQL plan are not a production capacity certification.

## References

- [Spring transactional proxy and self-invocation semantics](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- [Spring JDBC batch operations](https://docs.spring.io/spring-framework/reference/7.1/data-access/jdbc/advanced.html)
- [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/17/using-explain.html)
