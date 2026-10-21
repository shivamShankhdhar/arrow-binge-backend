# Arrow Binge backend — full API and domain design

Updated October 21, 2026. This inventory describes current v1 routes in source. The v2 acknowledgement/bootstrap/receipt section below is a proposal, not an available API.

## Current API inventory

All paths below are relative to /v1. Protected routes obtain their owner from AuthService.Principal. Public/auth route classification is implemented in SecurityConfiguration and must be tested alongside controllers.

| Area | Methods and paths | Design responsibility |
|---|---|---|
| Login | GET /auth/config; POST /auth/challenge, /auth/google | Google client configuration, challenge ID/device ID/id token validation; never log identity tokens |
| Session | POST /auth/refresh, /auth/logout | Stable refresh request identity, rotation/reuse handling, revocation |
| Account | GET /me, /me/sessions; DELETE /me/sessions/{id} | Owner-only profile/session management |
| Privacy | GET /me/export; DELETE /me | Export profile/save/sessions/rankings/events; recent-auth and deletion behavior tested |
| Save | GET /save; POST /save/sync, /save/reset | Monotonic merge, attempts, epoch and operation replay; reset is destructive and requires recent authentication |
| Regular rankings | GET /leaderboard; PUT/DELETE /leaderboard/me | Enrollment nickname, top scores and own rank; leaving does not erase save |
| Event schedule | GET /events; GET /events/tournament/standings | Public calendar/competition information; target is side-effect-free reads |
| Event account | GET /events/me; PUT/DELETE /events/tournament | Personal progress/history, enrollment and leaving |
| Event run | POST /events/runs; POST /events/runs/{id}/finish | mode/day ticket request; pulledIds completion validated against ticket/catalogue |

Root, actuator and cleanup are operational surfaces rather than gameplay APIs. Inventory deployment exposure and access controls in operational tests; do not assume every Actuator endpoint is public or enabled.

## Domain behavior

Regular stars are per-level best progress, not event points. Save reset increments the effective epoch and prevents old requests resurrecting prior progress. Retry identity includes payload identity: the same operation ID cannot silently mean different work. Preferences have different conflict semantics from monotonic stars/unlocks.

Regular rankings order by stars then completed count; user ID makes display ordering deterministic, while equal score tuples share rank. Nicknames accept 2–24 letters/numbers/spaces/dashes/underscores in the inspected service. Identity is user ID, never nickname. The own-row query must work when outside top 50.

Daily keys and tournament weeks are UTC. Weeks start Monday and end at the next Monday boundary. Daily rotation is shared with Android EventCalendar. Weekly rotation selects 50 boards from IDs 1–50 deterministically. Run ownership, eligibility, stored expiry, content validity and uniqueness are checked before credit. Device wall time cannot decide acceptance. Same-ticket replay and a different ticket for an already credited board are different outcomes.

## Failure, privacy and compatibility design

Map malformed input to a typed client error, expired/revoked credentials to authentication recovery, conflicts to explicit domain outcomes, and transient capacity/network faults to bounded retry. Cancellation/lost response must not imply rollback: recover by stable operation identity. Preserve v1 behavior for supported clients while adding v2 capability detection; never reinterpret unsupported/error responses as empty saves.

Exports can be larger than ordinary reads and need an explicit size/streaming policy; do not leak another session's secret material. Account deletion must consider relational cascades, receipts, recovery identities, logs and backup retention. This is an engineering retention requirement, not a claim of legal certification.

## Operational design acceptance

Keep required secrets fail-closed, protected cleanup, bounded requests/response pages, endpoint-appropriate rate budgets and Retry-After. Readiness should validate necessary dependencies without exposing secrets. Deploy additive migrations, smoke-test current and preceding supported clients, monitor error/latency/lock/pool trends and preserve data on rollback. Test restore from backup in an isolated environment.

The following detailed v2 design owns proposed progress/event field definitions; generate shared schema fixtures before implementing either client or server.

## Detailed progress and event reliability workstream

Retained subsystem specification. Proposed v2 behavior is not implemented merely by documenting it; historical audit results are not a new test run.

Status: proposed v2 contract, October 21, 2026. These routes/fields are not implemented yet. Android [design.md](../arrow-binge/docs/DESIGN.md) defines UI behavior; this file defines shared wire semantics.

### Common conventions

- Owner is resolved from authentication, never accepted as authority from a request body. Responses include ownerId so clients validate identity.
- IDs are UUIDs, times UTC ISO-8601, revisions nonnegative 64-bit integers. Stable level ID is distinct from display order.
- An operation ID is bound to a canonical validated payload hash; use deterministic ordering for set-valued fields. Same ID/different payload is a conflict.
- Null/absent score or rank means unknown/not applicable. Do not silently substitute 0 or 1.
- Errors: `{code, requestId, retryable, retryAfterSeconds?}` with appropriate 4xx/5xx status. Never include secrets or internal stack traces.
- Cap JSON bytes and nested arrays before expensive database work. Reject null collection elements explicitly. Suggested initial limits retain ≤1,000 levels/unlocks and ≤2,000 attempts per sync, with a 4 MiB hard ceiling; preferred new-client batches are much smaller.

### Capability and bootstrap

`GET /v2/account/bootstrap` returns:

```json
{
  "ownerId": "uuid",
  "protocolVersion": 2,
  "serverTime": "UTC instant",
  "save": {
    "epoch": 0, "serverRevision": 42, "hasProgress": true,
    "levels": [{"id": 7, "stars": 3}], "unlocked": [1, 7],
    "preferences": {"lastPlayed": 7, "sound": true, "haptics": true},
    "preferenceRevision": 5,
    "attempts": [], "nextAttemptCursor": null
  },
  "events": {"dailyHistoryCursor": "opaque or null", "activeRun": null},
  "capabilities": ["save-ack-v2", "event-receipt-v2"]
}
```

Illustrative values are not seed data. A successful empty save uses `hasProgress=false`; an unavailable database is an error, never an empty save. Bootstrap does not wait for top rankings/public schedule. Pages are capped initially at 100 items and 512 KiB; tune after profiling. Cursor is opaque, authenticated, owner/domain/epoch/revision-bound. If a reset or concurrent revision invalidates consistency, return CURSOR_STALE and restart from bootstrap while preserving local work. Do not accept a cursor from another owner.

`GET /v2/save/attempts?cursor=…` and `GET /v2/events/daily-history?cursor=…` complete restore. The terminal cursor indicates complete history, not merely the last 100 days.

For capability discovery, an old server's 404 is classified as unsupported protocol, not empty progress. The frontend uses an explicit tested v1 compatibility adapter or asks for a supported version; it does not parse incomplete v2 data as success.

### Regular save acknowledgement

`POST /v2/save/sync` accepts `{operationId, epoch, localRevision, levels, unlocked, attempts, preferences?}`. Attempts retain runId, checkpoint, contentVersion and closure; device identity comes from the authenticated session. Preference updates include expectedPreferenceRevision and changed fields. Safe progress merge can succeed while a preference conflict is returned explicitly in the acknowledgement; conflict fields must not be marked acknowledged locally.

Successful response:

```json
{
  "ack": {
    "ownerId": "uuid", "operationId": "uuid", "epoch": 0,
    "acknowledgedLocalRevision": 12, "serverRevision": 43,
    "committedAt": "UTC instant"
  },
  "preferenceOutcome": "applied|unchanged|conflict|not-requested",
  "preferenceRevision": 6,
  "canonicalProgressCursor": "opaque or null"
}
```

No pending item is removed before this response is schema/identity/epoch/operation validated and durably applied. Local edits beyond revision 12 remain dirty. Cross-device canonical state is reconciled using the returned cursor/bootstrap, not by claiming the acknowledgement uploaded unseen local changes.

Replay returns the same committed ack. The current reset epoch takes precedence: after reset, an old-epoch operation returns SAVE_RESET_REQUIRED with current epoch and never revives old data. A receipt lookup `GET /v2/save/operations/{operationId}` uses the same ownership and epoch checks; 404 means outcome unknown, so retry the unchanged operation rather than inventing a replacement.

### Guest import

`POST /v2/save/import` carries guestId, operationId, target epoch and bounded guest progress. The server claims guestId and merges in one transaction. Same owner/import replay returns the original receipt; another owner gets GUEST_ALREADY_IMPORTED. Preserve import claims/tombstones according to deletion/privacy policy; never bypass a conflict by silently generating a fresh guest ID. Recovery after reinstall must not reassign a claimed guest to another owner.

### Tournament ticket

`POST /v2/events/runs` returns `{ticketId, week, mode, levelId, contentVersion, arrowCount, expiresAt, completedBoardCount}`. Reuse returns stored values, including expiry. The server selects the next uncredited board. Keep contentVersion with the ticket until it is resolved. Returning a currently pending ticket is allowed; issuing a different next board requires the prerequisite receipt.

### Tournament completion receipt

`POST /v2/events/runs/{ticketId}/finish` accepts `{completionId, contentVersion, pulledIds}`. Client persists completionId before the first request. The server validates ticket ownership, expiry, content and exact unique ID coverage. Points are server-derived from the issued catalog revision.

```json
{
  "receiptId": "uuid", "completionId": "uuid", "ticketId": "uuid",
  "ownerId": "uuid", "week": "2026-10-19", "levelId": 7,
  "contentVersion": "version", "arrowCount": 40,
  "outcome": "credited",
  "awardedPoints": 40, "totalPoints": 120,
  "completedBoards": 3, "playerRevision": 3,
  "rankBefore": 12, "rankAfter": 10,
  "rankObservedAt": "UTC instant", "acceptedAt": "UTC instant",
  "nextBoardAvailable": true
}
```

Numbers illustrate the shape, not guaranteed awards or rank changes. Persist the receipt in the same transaction as credit. The client replaces totals by playerRevision; it never adds awardedPoints repeatedly.

- Same ticket and equivalent completion retry: return the original receipt, even if the deadline has since passed. The stored receipt is checked before rejecting an expired *new* finish.
- Same completion ID reused for different ticket/payload: COMPLETION_REUSED.
- A different ticket for an already credited player/week/board: `outcome=already_credited`, `awardedPoints=0`, original credit reference and authoritative totals. UI says Already credited, never “0 arrows cleared.”
- New expired finish: EVENT_ENDED, no new credit. Persist or otherwise recover its terminal resolution; distinguish it from transient failure.
- Rank is an observation within the completion snapshot, not a promise to remain at that rank. Equal ranks are valid. Client animates once per receipt and refreshes current standings independently.
- `GET /v2/events/runs/{ticketId}/receipt` recovers lost responses; auth and ownership checks apply. Receipt total may be older than a newer playerRevision already on device and must not overwrite it.

Daily finish uses the same identity/replay guarantees but rank/weekly points are absent and outcome is daily_completed or already_completed. Daily history restore is independent of tournament standings.

### Logout and reset

Logout does not itself upload client work. Frontend completes its local barrier and regular/event receipt checks before calling `POST /v2/auth/logout`. Server revocation is idempotent for the same session and returns explicit confirmation. Do not make unrelated rankings refresh a prerequisite. Failure to revoke does not falsely claim remote revocation; client may still perform explicit local-only sign-out.

Reset increments epoch transactionally, invalidates obsolete operations, and returns authoritative empty/current progress. Account deletion applies ownership/recent-auth policy and removes private records consistently. Both remain explicit user actions.

### Compatibility and rollout

1. Publish OpenAPI/JSON schema and cross-repository fixtures for these proposed shapes before coding clients.
2. Add new migrations, bounded repositories and v2 handlers; retain v1 routes/response fields and idempotency semantics.
3. Implement Android typed adapters and capability negotiation; v1 fallback must still use honest pending states and never fabricate v2 receipts.
4. Stage old app/new server and new app/old server cases. Gate feature enablement until both sides pass the receipt matrix.
5. Roll back by disabling v2 use, not deleting user data or dropping migrated tables. Keep old readers compatible during the rollout window.

Changing this contract requires updating both TASKS.md files and the shared fixture tests. This document is canonical; avoid separately evolving copied payload definitions.
