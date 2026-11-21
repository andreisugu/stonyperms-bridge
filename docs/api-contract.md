# API contract used by the bridge (v0.2 / Schema v1)

The authoritative specification lives in [`gate-stonyperms/docs/http-api.md`](https://github.com/andreisugu/gate-stonyperms/blob/main/docs/http-api.md). This page documents the subset and exact semantics `stonyperms-bridge` (`0.2.0`) relies on.

## Base URL, Authentication & Scope Tiers

- Base URL: `http://127.0.0.1:8737` by default (use WireGuard/Tailscale or HTTPS off-loopback).
- Every `/v1/*` request sends `Authorization: Bearer <token>`.
- `GET /healthz` is unauthenticated and returns `{"status":"ok"}`.
- **Two token tiers:**
  - **Read token** (`STONYPERMS_BRIDGE_TOKEN`, `api.token`, or `api.token-file` / `tokenFile`): required on startup (`>= 16` chars). Used for SSE, player/group/user/track reads, and `/v1/check`.
  - **Write token** (`STONYPERMS_BRIDGE_WRITE_TOKEN`, `api.write-token` / `writeToken`, or `api.write-token-file` / `writeTokenFile`): required only when `write.enabled = true` (`false` by default). Used exclusively for mutations, `GET /v1/logs`, and `POST /v1/reload`.
- Both Paper and Fabric resolve token files as a **single trimmed string** (`Files.readString(path).trim()`). Do not copy a multi-line proxy rotation file verbatim to a bridge backend.

## Read & Event Endpoints Used

### Health & Revision

- `GET /v1/health` -> `{"status":"ok","schemaVersion":1,"revision":42,"writeEnabled":false,"sseEnabled":true}` (`schemaVersion` must be `1`).
- `GET /v1/revision` -> `{"revision":42}`.

### Live SSE Stream (`GET /v1/events`)

When `sse.enabled` is `true` (default), `StonyPermsEventStream` opens a long-lived `text/event-stream` connection using the read token without a per-request read timeout:

- Expects `event: connected` (`{"revision":<long>,"generation":"<string>"}`), `: keepalive` comments (every 25 s on the proxy), and `ChangeEvent` frames:
  - `user_updated`, `user_deleted`, `group_updated`, `group_deleted`, `track_updated`, `track_deleted`, `reload`, `expiry_cleanup`.
  - JSON payload: `{revision, type, uuid?, username?, group?, track?, actor?, timestamp}`.
- Backends refresh affected online players on `user_updated` / `user_deleted` (invalidating the cache first on delete on Paper) and refresh all online players on `group_updated` / `group_deleted` / `reload` / `expiry_cleanup`.

### Player Snapshot & Batch Snapshots

- `GET /v1/players/{uuid}?<contexts>` with optional `If-None-Match` (`"sp-<generation>-<revision>-<fingerprint>"`):
  - `200 OK` returns `schemaVersion: 1`, `revision`, `generatedAt`, `ttlSeconds`, `user` (`uuid`, `username`, `primaryGroup`, `synthetic`, `meta`, `prefix`, `suffix`), weight-ordered `groups`, and evaluation-ordered `nodes`.
  - `304 Not Modified` extends the cached snapshot freshness window without re-applying unchanged permissions.
- `POST /v1/players/batch` (`{"uuids":[...],"contexts":{...}}`, up to 100 UUIDs) -> `{"schemaVersion":1,"revision":...,"players":{"<uuid>": <snapshot>}}`.

### Authoritative Check & Read Diagnostics

- `POST /v1/check` (`{"uuid":"...","permission":"...","contexts":{...}}`) -> `{"value":"true"|"false"|"undefined","source":"...","revision":...}`. Used by `/spb check`.
- `GET /v1/users?group=&limit=&offset=` -> paginated `UsersPage`.
- `GET /v1/users/by-name/{username}` -> `UserRecord` (`404 user_not_found` -> `Optional.empty()` in `resolveUser`).
- `GET /v1/users/{id}` -> `UserRecord` by UUID or username (used by `/spb user <target> info`).
- `GET /v1/groups`, `GET /v1/groups/{name}`, `GET /v1/groups/{name}/members` -> group list, details, and membership (`GroupMember` entries with `permanent`, `expiry`, `contexts`).
- `GET /v1/tracks`, `GET /v1/tracks/{name}` -> track definitions.

## Write & Admin Endpoints Used (Only When `write.enabled = true`)

When `write.enabled = true` and a write token is configured, `StonyPermsClient` calls write/admin endpoints with:

- `Authorization: Bearer <writeToken>`
- `X-StonyPerms-Actor: <actor>` (omitted if blank; `/spb` defaults to player name / `"console"`, while programmatic API callers default to `write.default-actor`, or `"paper:<server-name>"` / `"paper"` on Paper and `"fabric:<serverName>"` / `"fabric"` on Fabric)
- Optional `Idempotency-Key: <key>` (inspects `X-Idempotency-Replayed: true` into `MutationResult.replayed()`)

Endpoints mapped in `StonyPermsClient`:

- **Users:** `POST /v1/users` (`ensureUser` with `createOffline` flag), `DELETE /v1/users/{id}`, `POST /v1/users/{id}/parents` (`mode`: `add` | `set` | `accumulate`, `durationSeconds`, `expiry`, `contexts`, `setPrimary`), `DELETE /v1/users/{id}/parents/{group}` (contexts via query params), `PUT /v1/users/{id}/primary-group`, `POST /v1/users/{id}/permissions`, `DELETE /v1/users/{id}/permissions`, `PUT/DELETE /v1/users/{id}/meta/{key}`, `POST /v1/users/{id}/prefixes`, `DELETE /v1/users/{id}/prefixes/{priority}`, `POST /v1/users/{id}/suffixes`, `DELETE /v1/users/{id}/suffixes/{priority}`, `POST /v1/users/{id}/promote`, `POST /v1/users/{id}/demote`, `POST /v1/users/{id}/clear`, `POST /v1/users/{id}/batch`.
- **Groups:** `POST /v1/groups`, `PATCH /v1/groups/{name}`, `DELETE /v1/groups/{name}`, `POST/DELETE /v1/groups/{name}/permissions`, `POST /v1/groups/{name}/parents`, `DELETE /v1/groups/{name}/parents/{parent}`, `PUT/DELETE /v1/groups/{name}/meta/{key}`, `POST /v1/groups/{name}/prefixes`, `DELETE /v1/groups/{name}/prefixes/{priority}`, `POST /v1/groups/{name}/suffixes`, `DELETE /v1/groups/{name}/suffixes/{priority}`.
- **Tracks:** `POST /v1/tracks`, `PUT /v1/tracks/{name}`, `DELETE /v1/tracks/{name}`.
- **Admin:** `GET /v1/logs` (`limit`, `targetType`, `target`, `actor`), `POST /v1/reload`.

## Client Behavior & Compatibility

- Timeouts: `connectTimeoutMs` (default 1500 ms) and `requestTimeoutMs` (default 2500 ms) on standard requests; no redirects (`Redirect.NEVER`).
- `4xx`/`5xx` and malformed JSON throw `ApiException(status, errorCode, message)` cleanly parsed from the JSON `{"error":"...","message":"..."}` envelope without leaking secrets.
- `schemaVersion` must equal `1`; unknown JSON fields are ignored cleanly by Gson.
