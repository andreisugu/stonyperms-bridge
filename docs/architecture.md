# Architecture (v0.2)

## Goal

Make StonyPerms on Gate the single source of truth for Paper and Fabric server-side permissions while supporting near-real-time invalidation (`GET /v1/events`), plugin/mod developer integrations (`StonyPermsBridgeAPI`, PlaceholderAPI, Vault), and opt-in cross-server administration from trusted backends without moving StonyPerms' storage or duplicating its evaluation engine.

## Design choice (Proxy-Owned Engine + Scoped HTTP/SSE API)

StonyPerms on Gate retains exclusive ownership of storage (`atomic JSON`), inheritance, weights, wildcards, contexts, expiry, affixes, metadata, and the audit log. Backends talk to Gate's `api.Server` over HTTP/SSE rather than reading storage files or sharing SQLite/Postgres:

- **One evaluation implementation:** `gate-stonyperms` resolves effective snapshots (`EffectiveUser`) and single checks (`EvaluateUser`); the core Java `PermissionResolver` mirrors that precedence order cleanly.
- **No shared database coupling:** Backends on remote hosts communicate strictly over tailnet/WireGuard or TLS without filesystem locks or DB credentials.
- **Near-real-time invalidation + self-healing polling:** SSE (`GET /v1/events`) triggers immediate targeted refreshes while `ETag` / `If-None-Match` periodic refreshes recover automatically from transient stream drops.
- **Opt-in, fail-closed write delegation:** Cross-server mutations go through Gate's HTTP write routes with separate write-tier authentication, `X-StonyPerms-Actor` attribution, and `Idempotency-Key` support, so Gate's in-memory manager and audit log remain the sole writer.

## Components

```text
┌──────────────────────────────────────────────────────────┐
│ Gate + gate-stonyperms                                   │
│  Manager ── Engine ── EventBus (ChangeEvent)             │
│      │                                                   │
│  api.Server (net/http, Read Tier + Opt-In Write Tier)    │
└──────┬───────────────────────────────────────────────────┘
       │  Read:  GET /v1/events (SSE), GET /v1/players/{uuid}, POST /v1/players/batch,
       │         POST /v1/check, GET /v1/users*, /v1/groups*, /v1/tracks*, /v1/health
       │  Write: POST/PUT/PATCH/DELETE /v1/users*|/v1/groups*|/v1/tracks*|/v1/reload,
       │         GET /v1/logs  (Bearer write token, X-StonyPerms-Actor, Idempotency-Key)
       ▼
┌──────────────────────────────────────────────────────────┐
│ stonyperms-bridge/core                                   │
│  StonyPermsClient           (read + write + idempotency) │
│  StonyPermsEventStream      (GET /v1/events + backoff)   │
│  SnapshotCache              (TTL + stale + rev guard)    │
│  PermissionResolver         (exact Engine ordering port) │
│  BridgeService              (fail-closed / stale policy) │
│  DefaultStonyPermsBridgeAPI + BridgeCommandHandler       │
└──────┬───────────────────────────────────────┬───────────┘
       │                                       │
       ▼                                       ▼
 Paper plugin (paper)                     Fabric mod (fabric)
  ├─ Attachment materializer               ├─ fabric-permissions-api listeners
  ├─ PlaceholderAPI (%stonyperms_*%)       ├─ FabricBridgeRuntime (SSE + refresh)
  ├─ Vault Permission & Chat (softdepend)  ├─ StonyPermsEvents (USER_UPDATED /
  ├─ Bukkit ServicesManager API + Event    │    CHANGE_RECEIVED)
  └─ /spb command                          └─ /spb command
```

### Proxy Side (`gate-stonyperms`)

`api.Server` starts when `api_enabled = true`. `api_write_enabled` defaults to `false`; if `api_write_enabled = true` and write-token loading fails, Gate disables the write tier instead of falling back to read tokens. Overlapping tokens between read and write tiers are rejected by `api.New`. Per-IP token buckets (`api_rate_limit_*`, `api_write_rate_limit_*`) protect both tiers, and `GET /v1/events` fans out typed `ChangeEvent` records from `manager.EventBus`.

### Core (`core`)

- `StonyPermsClient`: JDK `HttpClient`, Gson, read bearer token + optional write bearer token, `connectTimeoutMs` (default 1500 ms) / `requestTimeoutMs` (default 2500 ms), no redirects (`Redirect.NEVER`), `ETag` (`If-None-Match`), `X-StonyPerms-Actor`, `Idempotency-Key`, and `X-Idempotency-Replayed` inspection. Never exposes tokens or response bodies in exception messages.
- `StonyPermsEventStream`: Daemon SSE client for `GET /v1/events` with no request read timeout on the stream itself and exponential backoff between `reconnectMinMs` (default 1000 ms) and `reconnectMaxMs` (default 30000 ms).
- `SnapshotCache`: Keyed by `(uuid, contextKey)` with fresh TTL (`ttlSeconds`) and grace window (`staleGraceSeconds`). Enforces monotonic revision ordering (ignores stale revisions) and refreshes freshness cleanly on HTTP `304`.
- `PermissionResolver`: Exact port of `engine.Evaluate` precedence:
  1. Direct user nodes (`user: true`) first.
  2. Inherited groups in snapshot array order (descending weight).
  3. Within each source, exact keys beat wildcards, and the longest matching wildcard wins.
- `BridgeService` & `DefaultStonyPermsBridgeAPI`: Fail-closed / stale-grace policy, synchronous cache accessors (`getCachedSnapshot`, `checkCached`, `getPrimaryGroup`, `getGroups`, `inGroup`, `getPrefix`, `getSuffix`, `getMeta`, `getAllMeta`), async authoritative reads (`fetchSnapshot`, `fetchSnapshotsBatch`, `listUsers`, `resolveUser`, `fetchUserRecord`, `fetchAllGroups`, `fetchGroup`, `fetchGroupMembers`, `fetchTracks`, `fetchTrack`, `checkAuthoritative`, `fetchHealth`), reactive listeners (`onUserUpdated`, `onChangeEvent`), and write methods that fail closed before HTTP when `!isWriteEnabled()`.
- `BridgeCommandHandler`: Shared `/spb` command parser and tab completer across Paper and Fabric, including duration parsing (`DurationParser`: `s`/`m`/`h`/`d`/`w`/`mo`/`y`/`permanent`, capped at 10 years), trailing `key=value` contexts (`ContextParser`), quoted argument normalization, and `withOnlineAutoEnsure` for online targets not yet persisted on Gate.

### Paper Adapter (`paper`)

- Materializes registered Bukkit permissions (`PluginManager.getPermissions()`) plus literal nodes from the snapshot into one `PermissionAttachment` per player on the main server thread.
- When `apply-to-ops: false`, ops are skipped (`applyIfOnline` leaves ops untouched). When `true` (default), ops also receive the attachment.
- Hooks SSE events (`user_updated` / `user_deleted` invalidate/refresh the online target; `group_updated` / `group_deleted` / `reload` / `expiry_cleanup` refresh all online players), joins, world changes, and periodic polling (`refresh-seconds`).
- Registers `StonyPermsBridgeAPI` in Bukkit `ServicesManager`, emits synchronous `StonyPermsUserUpdateEvent` on applied snapshot changes, and registers `PlaceholderAPI` (`%stonyperms_*%`) and `Vault` (`Permission` & `Chat`) only when `integrations.*` is `true` and the optional plugin is enabled (`softdepend: [Vault, PlaceholderAPI]`; neither API jar is shaded).

### Fabric Adapter (`fabric`)

- Registers `fabric-permissions-api` listeners: `PermissionCheckEvent`, `OptionRequestEvent`, `OfflinePermissionCheckEvent`, and `OfflineOptionRequestEvent`.
- `applyToOps` (`true` default): when `false`, `FabricBridgeRuntime.isOpBypassed` returns `true` for server ops so `fabric-permissions-api` defers (`TriState.DEFAULT`) rather than applying StonyPerms grants/denials. Independently, `/spb` retains console and `playerList.isOp(...)` break-glass authorization.
- Hooks SSE events (`user_updated` / `user_deleted` -> targeted player refresh if online; `group_updated` / `group_deleted` / `reload` / `expiry_cleanup` -> `refreshAll`), `JOIN`, and periodic polling (`refreshSeconds`).
- Exposes `StonyPermsBridgeMod.api()` and fires `StonyPermsEvents.USER_UPDATED` (on the server thread when available) and `StonyPermsEvents.CHANGE_RECEIVED`.

## Refresh, Invalidation & Failure Modes

| Condition | Behavior |
| --- | --- |
| Mutation on Gate while SSE connected | Proxy emits typed `ChangeEvent` -> backend triggers targeted or full refresh within milliseconds |
| SSE disconnected or event dropped | Exponential backoff reconnect + periodic `refresh-seconds` poll with `If-None-Match` (`304 Not Modified` extends TTL) |
| API down, no cache, `fail-closed=true` | Deny bridge-managed permissions (`Tri.FALSE` / empty attachment); platform defaults for unmanaged nodes |
| API down, cache within `stale-grace-seconds` | Serve cached snapshot when `use-stale-on-error=true` |
| API down, cache past `stale-grace-seconds` | Deny if `fail-closed=true`, else fall back to platform defaults |
| `write.enabled=false` or missing write token | Write API methods and mutating `/spb` subcommands fail closed without issuing HTTP writes |
| Missing read token on startup | Adapter logs a clear error and disables itself cleanly without crashing the server |
