# Arrow Binge account API

Spring Boot 4.1 / Java 17 / PostgreSQL 17. Source configuration contains no database credentials. The original supplied credential has been moved into ignored `.env.local` with owner-only file permissions; it is not loaded automatically.

## Run locally

Copy `.env.example` to an ignored environment file and supply values. Export them in your deployment environment, then run `./mvnw spring-boot:run`. Java 17 or newer is required. For the local PostgreSQL container use `docker compose --env-file .env.local up -d postgres` (set an appropriate local database password, not a hosted database credential). Flyway applies migrations on startup. The API is port 8080 by default; put TLS at the production ingress.

Required configuration: `DB_URL` (JDBC URL), `DB_USERNAME`, `DB_PASSWORD`, `GOOGLE_WEB_CLIENT_ID`, `GOOGLE_ANDROID_CLIENT_IDS` (comma separated allowed authorized parties), and random `SESSION_SECRET` (32+ characters). The web client ID must match Android's server audience. Session secret rotation invalidates retry derivation and should accompany session revocation.

## Routes

- `POST /v1/auth/challenge`: single-use nonce, five-minute expiry.
- `POST /v1/auth/google`: challengeId, idToken, deviceId; returns profile and opaque tokens.
- `POST /v1/auth/refresh`: refreshToken and stable requestId; replay the same requestId after a lost response.
- `POST /v1/auth/logout`: revoke current session.
- `GET /v1/me`, `GET /v1/me/sessions`, `DELETE /v1/me/sessions/{id}`.
- `GET /v1/save`, `POST /v1/save/sync`: authenticated account save; device ID comes from the session, never a client-selected owner.
- `POST /v1/save/reset`: expected epoch, recent authentication required.
- `GET /v1/me/export`, `DELETE /v1/me`: recent authentication required.
- `GET /actuator/health`: deployment health.

Protected endpoints require `Authorization: Bearer <accessToken>`. Access tokens expire after ten minutes, refresh inactivity expiry is thirty days, absolute session lifetime is ninety days. Do not log headers, ID tokens, request bodies, or database credentials.

## Save protocol

The Android `AccountStore.prepare` implementation is the request contract. Stable level IDs identify objects; public collection numbers must not be used as IDs. The `catalog.json` manifest includes all 1000 levels and their next stable IDs, matching app commit 7bd69df. The current content version is `discovery-v3`; bump it when arrow geometry changes.

An operation UUID is bound to its payload hash. Retries are idempotent; reusing it for different data returns `OPERATION_REUSED`. Per-account transactions serialize with a user-row lock. Stars take the maximum, unlocks union, attempts retain device/run identity and increasing checkpoints. Closed runs cannot reopen. Guest imports have a unique guest UUID receipt. Epoch mismatch returns `SAVE_RESET_REQUIRED`; a client must download the new save and invalidate/archive its stale pending payload.

Snapshots have a 4 MiB request limit and at most 1000 star records/2000 attempt records. This is a bounded snapshot implementation; incremental cursor synchronization is a future optimization. Offline save data is client-authored: this API is not a competitive anti-cheat validator.

## Tests

Use a disposable local PostgreSQL database; never point tests at production. For example:

```
TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/arrow_test TEST_DB_USERNAME=arrow_test TEST_DB_PASSWORD= ./mvnw test
```

Tests mock Google identity verification but exercise PostgreSQL migrations, transactions and HTTP authorization. Real Google ID-token validation requires the configured staging client and Android signing certificate.

## Deployment gates

The Android implementation status document in `arrow-binge/docs/backend/IMPLEMENTATION_STATUS.md` records the complete remaining rollout list. Wallet/reward tables are reserved only: cloud hint allocation and AdMob SSV are not yet enabled; client reward callbacks cannot credit cloud balances. Keep hints device-local until this is implemented and validated. Production still needs privacy/deletion web pages, data-retention decisions, TLS ingress/body/rate limits, backup/restore drills, maintenance cleanup, monitoring and staging OAuth tests.

## Deploy to Vercel (Container Runtime)

This backend runs on Vercel as a serverless function via Vercel's **Container Runtime** using `Dockerfile.vercel` and `vercel.json`.

### 1. Prerequisites
- A Vercel account (Hobby or Pro).
- A hosted PostgreSQL instance (e.g. Neon, Supabase, or AWS RDS).
- Google OAuth credentials configured for Android and Web.

### 2. Configure Environment Variables in Vercel
Add the following in your Vercel Project Settings (**Settings > Environment Variables**):

| Variable | Description | Example / Note |
|---|---|---|
| `DATABASE_URL` | *(Recommended)* Single connection string | `postgresql://neondb_owner:password@host/neondb?sslmode=require` (auto-parsed into JDBC URL, user, pass) |
| `DB_URL` | JDBC URL for PostgreSQL (if not using DATABASE_URL) | `jdbc:postgresql://<host>:5432/<db>?sslmode=require` |
| `DB_USERNAME` | Database username | E.g. `neondb_owner` |
| `DB_PASSWORD` | Database password | Secret database password |
| `GOOGLE_WEB_CLIENT_ID` | Web OAuth Client ID | Server audience matching Android client |
| `GOOGLE_ANDROID_CLIENT_IDS` | Allowed Android Client IDs | Comma-separated package client IDs |
| `SESSION_SECRET` | 32+ character random secret | Session token derivation secret |
| `PUBLIC_URL` | Deployed Vercel URL | `https://your-project.vercel.app` |
| `CRON_SECRET` | (Optional) Secret for cron cleanup | Vercel Cron sends `Authorization: Bearer <CRON_SECRET>` |


### 3. Deploy
- **Via Vercel Web Dashboard**: Connect the Git repository containing `arrow-binge-backend` (set the root directory to `arrow-binge-backend` if deploying from a monorepo). Vercel detects `Dockerfile.vercel` and builds the OCI container image automatically.
- **Via Vercel CLI**:
  ```bash
  npm i -g vercel
  cd arrow-binge-backend
  vercel
  vercel --prod
  ```

### 4. Verification
After deployment, verify the endpoints:
- `GET https://your-project.vercel.app/` -> returns `{"status":"UP","service":"arrow-binge-backend","version":"discovery-v3"}`
- `GET https://your-project.vercel.app/health` -> returns `{"status":"UP"}`
- `POST https://your-project.vercel.app/v1/auth/challenge` -> returns `{"challengeId":"...","nonce":"..."}`

## Daily challenges and weekly tournaments

Flyway V3 adds isolated event tickets, daily completions, tournament profiles and per-board score receipts. `GET /v1/events` is public. Authenticated routes are `GET /v1/events/me`, `PUT/DELETE /v1/events/tournament`, `POST /v1/events/runs`, and `POST /v1/events/runs/{id}/finish`. Join with `{ "nickname": "Puzzle Fox" }`; start with `{ "mode": "tournament" }` or `{ "mode": "daily", "day": "2026-10-19" }`; finish with `{ "pulledIds": [0,1,2,3] }` matching the issued board's full catalog ID range.

All dates and cutoffs use UTC; weeks reset Monday. The server selects the board and rejects incomplete, foreign, future-day or expired submissions. Tournament score is 1 point per arrow on each fully completed board, once per player/week/board; retries are idempotent and ties share ranks. Scores are client-reported completion receipts, not server-replayed puzzle solutions or prize-grade anti-cheat. A pending tournament result must reach the API before the week's end to count. Export includes event history and account deletion cascades all event records.

`GET /v1/auth/config` exposes the non-secret web OAuth audience to prevent stale app/server client-ID mismatches. Google signature, issuer, audience, expiry, subject and challenge nonce remain required. Additional Android `azp` restriction is applied when actual Android OAuth client IDs are configured; a missing list or a list containing only the web client ID no longer rejects valid Android presenters. Register the package/signing certificates in Google Cloud and use the same project's web client audience. Detailed UI/rollout/test notes are in the app repository's `docs/events/README.md`.
