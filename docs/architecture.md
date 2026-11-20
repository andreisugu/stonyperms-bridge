# Architecture

## Goal

Make StonyPerms on Gate the single source of truth for Paper/Fabric server-side
permissions without moving StonyPerms' data or duplicating its write path.

## Options considered

### A. Read-only HTTP JSON API (chosen)

StonyPerms keeps ownership of users, groups, weights, inheritance, wildcards,
contexts, expiry, metadata, and audit state. Backends ask for a **resolved
snapshot** and cache it.

Strengths:

- One implementation of evaluation semantics; the bug surface stays on the
  proxy.
- No shared filesystem/database coupling; backends can run on other hosts.
- Language-agnostic, easy to test, easy to put behind TLS/tailnet.
- Read-only contract cannot corrupt permissions.
- Change detection is cheap (monotonic revision + ETag).

Costs:

- A network dependency, mitigated by caching and a stale window.
- The API must be operated securely (token, bind address, TLS).
- Snapshots are context-specific and point-in-time.

### B. Shared SQLite/Postgres (rejected)

- The current storage is atomic JSON with per-user files; exposing it as a
  database is a new storage engine, not a bridge.
- Two writers or direct readers bypass Gate's in-memory manager and audit log.
- SQLite on a network filesystem is unsafe; Postgres adds a service and
  credentials to both proxy and every backend.
- StonyPerms' evaluation needs group inheritance, weights, contexts, expiry,
  prefixes, and meta. A database client would still reimplement the engine.
- No clean invalidation story without an API anyway.

### C. Plugin messaging (rejected)

- Requires a connected player and a proxy-side channel; login-time checks and
  offline checks cannot be served.
- Request/response correlation, payload limits, and per-backend routing add
  complexity for no benefit over HTTP.
- Paper plugins and Fabric mods can both speak HTTP without a player.

## Components

```text
┌──────────────────────────────┐
│ Gate + gate-stonyperms       │
│  Manager ── Engine           │
│      │                       │
│  api.Server (net/http)       │
└──────┬───────────────────────┘
       │ GET /v1/players/{uuid}?server=&world=  (Bearer)
       │ GET /v1/revision, POST /v1/check
       ▼
┌──────────────────────────────┐
│ stonyperms-bridge/core       │
│  StonyPermsClient            │
│  SnapshotCache (TTL + stale) │
│  PermissionResolver          │
│  BridgeService               │
└──────┬───────────────┬───────┘
       │               │
       ▼               ▼
 Paper plugin      Fabric mod
 Attachment        PermissionCheckEvent
 materializer      provider
```

### Proxy side

`api.Server` is embedded in the existing `gate-stonyperms` plugin and starts
only when `api_enabled = true`. It calls new manager methods:

- `EffectiveUser(uuid, contexts, defaultGroup)` — immutable snapshot of
  resolved groups, nodes (with source and weight), meta, prefix, suffix.
- `EvaluateUser(uuid, permission, contexts, defaultGroup)` — authoritative
  single check.
- `Revision()` — bumped on every user/group mutation.
- `GroupsSnapshot()` / `GroupSnapshot()` — deep copies for diagnostics.

Unknown users are resolved synthetically against the default group and are not
persisted. Gate normally creates users on login, but this avoids a race at
backend join time.

### Core

- `StonyPermsClient`: JDK `HttpClient`, Gson, bearer token, timeouts, no
  redirects, ETag support. Error messages never include response bodies or
  tokens.
- `SnapshotCache`: keyed by `(uuid, contextKey)`, stores snapshot + ETag +
  fetch time. Fresh for `ttlSeconds`, then stale for the configured grace.
  Older revisions never overwrite newer ones. `touch` handles HTTP 304.
- `PermissionResolver`: exact port of `engine.Evaluate` ordering:
  user direct exact → user wildcard (longest) → each group by descending
  weight, exact then longest wildcard. A high-weight group wildcard therefore
  beats a low-weight group exact, matching the proxy.
- `BridgeService`: fail-closed/stale policy, refresh, materialization, and
  authoritative checks.

### Paper adapter

Bukkit's `Permissible.hasPermission` is a map lookup, not a hook, so a plugin
cannot answer arbitrary future nodes on demand. The adapter:

1. Collects the registered Bukkit permission universe
   (`PluginManager.getPermissions()`, which includes plugin.yml declarations).
2. Adds every literal node from the snapshot.
3. Resolves that universe through `PermissionResolver`.
4. Applies the resulting concrete map with one `PermissionAttachment` per
   player, removing the previous attachment first.

Refresh triggers: join, world change, interval, `/spb refresh`, `/spb reload`.
Values are applied on the main thread. Network I/O runs on a two-thread daemon
executor.

### Fabric adapter

`fabric-permissions-api` is the official extension point. The mod registers:

- `PermissionCheckEvent` — on-demand check, including wildcards and contexts,
  from the cache (no network on the server thread).
- `OptionRequestEvent` — prefix, suffix, and metadata.
- `OfflinePermissionCheckEvent` / `OfflineOptionRequestEvent` — last known
  snapshot for that UUID.
- `ServerPlayConnectionEvents.JOIN` — refresh snapshot.
- A scheduler that refreshes online players every `refresh-seconds`.

The cache is keyed by the same `(uuid, server, world)` context the mod sends to
the proxy, so permission checks on the server thread are a hash lookup plus the
in-memory resolver.

## Refresh and invalidation

- Every mutation on Gate bumps `revision` (users, groups, promote/demote).
- `/v1/revision` is a cheap poll; clients may poll it every N seconds.
- Snapshots carry `ETag: "sp-<revision>"`; a client refresh with
  `If-None-Match` gets `304` and only extends the fresh window.
- The adapters also refresh on join/world change and on a timer, so a missed
  revision poll is self-healing.
- Cache keys include context; a world change fetches a new snapshot instead of
  reusing the old one.

## Failure modes

| Condition | Behavior |
| --- | --- |
| API down, no cache, `fail-closed=true` | deny bridge-managed permissions |
| API down, no cache, `fail-closed=false` | platform defaults |
| API down, cache within stale grace | serve cached snapshot |
| API down, cache beyond stale grace | deny / platform defaults per flag |
| Snapshot present, node undefined | platform default (StonyPerms has no opinion) |
| Malformed response / 401 / 500 | treat as API failure, keep last good snapshot until grace expires |
| Token file missing on startup | adapter logs a clear error and disables itself |
| Paper unknown undeclared node | not materialized; see limitations |

## Why no write path

Writes stay on the Gate command surface. A backend write API would need
optimistic concurrency, audit attribution, permission checks on the backend,
and a story for group renames and cache invalidation. That is a separate
product decision, not required for read-side parity.
