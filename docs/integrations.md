# Chaonius Integrations & Developer API (v0.2)

This guide covers verified production patterns for integrating Chaonius plugins, Fabric mods, PayNow game-server fulfillment, the headless store (`chaonius-api` / `chaonius-store`), and network stats (`chaonius-network-stats`) with `gate-stonyperms` + `stonyperms-bridge` v0.2.

---

## 1. PayNow Console Fulfillment on Purpur Lobby (`paynow-bukkit-modern-1.0.7.jar`)

The Purpur 26.3 lobby (`cec14d00`) runs `paynow-bukkit-modern-1.0.7.jar` linked as `ChaoniusNetwork` (`627832377363660800`). Because PayNow dispatches console commands directly on the Purpur host while StonyPerms lives on Gate, use **least-privilege write delegation**:

- **Purpur lobby (trusted fulfillment node only):** set `write.enabled: true`, `write.default-actor: "paynow-lobby"` (or custom actor), and provision a **single-line `0600`** `plugins/StonyPermsBridge/write.token` (or `STONYPERMS_BRIDGE_WRITE_TOKEN`) matching Gate's `api_write_token_file`.
- **Survival & Creative Fabric backends:** keep `write.enabled: false` with **no write token** on disk, and keep `sse.enabled: true`. When PayNow runs a grant on the Purpur lobby, Gate bumps revision and pushes `user_updated` over SSE so Survival and Creative refresh within milliseconds.

### Recommended PayNow Console Commands (`/spb`)

PayNow executes console commands asynchronously without requiring the target player to have a pre-existing Gate record (`BridgeCommandHandler` runs `withOnlineAutoEnsure` if an online player is not yet stored):

```text
# Grant VIP for 30 days (idempotent stacking on renewals via accumulate)
spb user {player} group accumulate vip 30d

# Or initial grant / server-scoped grant
spb user {player} group add vip 30d
spb user {player} group add vip 30d server=survival

# Or promote along a cosmetic track / set profile metadata
spb user {player} promote cosmetic
spb user {player} meta set badge vip
```

Duration syntax (`DurationParser`): supports `s`, `m`, `h`, `d`, `w`, `mo` (30d), `y`, compound tokens such as `30d12h`, or `permanent` (max 10 years / `315360000` seconds). Trailing `key=value` tokens set StonyPerms contexts (`ContextParser`).

---

## 2. Website, Store (`chaonius-api` / `chaonius-store`) & Stats (`chaonius-network-stats`)

### Leaderboard & Profile Stats (`chaonius-network-stats` + `/stats/`)

Rather than firing per-player `GET /v1/players/{uuid}` calls for leaderboards or profile cards:

```http
POST /v1/players/batch
Authorization: Bearer <read-token>
Content-Type: application/json

{
  "uuids": [
    "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "f51d0bc4-8018-4c5c-8852-98aed2807587"
  ],
  "contexts": {"server": "survival"}
}
```

- Up to **100 UUIDs per request**, returning `{schemaVersion: 1, revision, players: {<uuid>: snapshot}}`.
- Runs strictly with the **read-tier** bearer token on the tailnet/backend API (`chaonius-api` / `chaonius-network-stats`), never exposing tokens to the browser.

### Headless Store Rank Fulfillment (`chaonius-store` / `chaonius-store-agent.service`)

External delivery services (`chaonius-store-agent.service` on `freeVM` at `127.0.0.1:8799` called by `chaonius-api` webhooks) connect over tailnet/TLS directly to Gate's unified HTTP API (`:8737`, replacing the earlier separate `:8738` draft in `stonyperms-write-api-spec.md`) using a **write-tier** bearer token:

1. **Ensure the user record exists** (explicitly enabling `"createOffline": true` only when you intentionally accept offline-UUID fallback for buyers who have never joined Gate; otherwise resolve the Mojang UUID first and pass `"uuid"` with `"createOffline": false`):

   ```http
   POST /v1/users
   Authorization: Bearer <write-token>
   X-StonyPerms-Actor: chaonius-store
   Idempotency-Key: paynow-ensure-<order_uuid>
   Content-Type: application/json

   {
     "uuid": "f51d0bc4-8018-4c5c-8852-98aed2807587",
     "username": "BuyerName",
     "createOffline": false
   }
   ```

2. **Grant or stack the rank package atomically:**

   ```http
   POST /v1/users/f51d0bc4-8018-4c5c-8852-98aed2807587/parents
   Authorization: Bearer <write-token>
   X-StonyPerms-Actor: chaonius-store
   Idempotency-Key: paynow-grant-<order_uuid>
   Content-Type: application/json

   {
     "group": "vip",
     "mode": "accumulate",
     "durationSeconds": 2592000,
     "setPrimary": false
   }
   ```

   - For permanent cosmetic ranks, set `"mode": "add"` and `"durationSeconds": 0`.
   - For chargebacks / refunds, call `DELETE /v1/users/{uuid}/parents/{group}` with `Idempotency-Key: paynow-revoke-<order_uuid>`.
   - Gate deduplicates matching retries for **15 minutes** (4,096-entry in-memory cache), returning `X-Idempotency-Replayed: true`, or `409 idempotency_conflict` if a key is reused with different query/body bytes. Keep durable `delivered` order state in `chaonius-store`'s own database for retries beyond 15 minutes or across Gate restarts.

---

## 3. Plugin & Mod Developer API (`StonyPermsBridgeAPI`)

All synchronous cache accessors are safe on the main server thread. Authoritative reads and cross-server writes return `CompletableFuture` executed on the bridge I/O executor. Write methods fail closed (`CompletableFuture.failedFuture` / `isWriteEnabled() == false`) unless `write.enabled = true`.

### Lookup & Events on Paper

```java
// Lookup via Bukkit ServicesManager or plugin accessor
Optional<StonyPermsBridgeAPI> apiOpt = StonyPermsBridgePlugin.getApi();
RegisteredServiceProvider<StonyPermsBridgeAPI> rsp =
        Bukkit.getServicesManager().getRegistration(StonyPermsBridgeAPI.class);

// Listen on main server thread when a player's applied snapshot updates
@EventHandler
public void onStonyPermsUpdate(StonyPermsUserUpdateEvent event) {
    Player player = event.getPlayer();
    Snapshot snapshot = event.getSnapshot();
    long prevRevision = event.getPreviousRevision();
}
```

### Lookup & Events on Fabric

```java
// Lookup via mod accessor
StonyPermsBridgeAPI api = StonyPermsBridgeMod.api();

// Register Fabric events (USER_UPDATED fires on server thread when running)
StonyPermsEvents.USER_UPDATED.register((uuid, snapshot, previousRevision) -> {
    // handle updated snapshot
});

StonyPermsEvents.CHANGE_RECEIVED.register(changeEvent -> {
    // handle raw SSE ChangeEvent (user_updated, group_updated, reload, etc.)
});
```

Core reactive subscriptions are also available directly on `StonyPermsBridgeAPI` returning `AutoCloseable` handles:

- `api.onUserUpdated((uuid, snapshot) -> ...)`
- `api.onChangeEvent(event -> ...)`

### Key `StonyPermsBridgeAPI` Methods

- **Non-blocking cache reads:** `getCachedSnapshot(uuid, contexts)`, `checkCached(uuid, perm, contexts)`, `getPrimaryGroup(uuid, contexts)`, `getGroups(uuid, contexts)`, `inGroup(uuid, group, contexts)`, `getPrefix(uuid, contexts)`, `getSuffix(uuid, contexts)`, `getMeta(uuid, key, contexts)`, `getAllMeta(uuid, contexts)`.
- **Async authoritative reads:** `fetchSnapshot`, `fetchSnapshotsBatch`, `listUsers`, `resolveUser`, `fetchUserRecord`, `fetchAllGroups`, `fetchGroup`, `fetchGroupMembers`, `fetchTracks`, `fetchTrack`, `checkAuthoritative`, `fetchHealth`.
- **Async writes & admin:** `isWriteEnabled()`, `defaultActor()`, `ensureUser`, `deleteUser`, `addParent` (with `ParentMode.ADD | SET | ACCUMULATE`), `removeParent`, `setPrimaryGroup`, `setPermission`, `unsetPermission`, `setMeta`, `unsetMeta`, `setPrefix`, `removePrefix`, `setSuffix`, `removeSuffix`, `promote`, `demote`, `clearUser`, `executeBatch(idOrName, List<BatchOp>)`, group CRUD (`createGroup`, `patchGroup`, `deleteGroup`, group perms/parents/meta/affixes), track CRUD (`createTrack`, `putTrack`, `deleteTrack`), `fetchLogs`, `triggerRemoteReload`.

---

## 4. PlaceholderAPI & Vault (Paper)

Both integrations are **zero-shading** (`compileOnly` + `softdepend: [Vault, PlaceholderAPI]` in `plugin.yml`) and guarded by `integrations.placeholderapi: true` and `integrations.vault: true` in `config.yml`.

### PlaceholderAPI Expansion (`%stonyperms_*%`)

Registered with identifier `stonyperms` and `persist() = true` (`StonyPermsPlaceholderExpansion`):

| Placeholder | Output | Notes |
| --- | --- | --- |
| `%stonyperms_primary_group%` | Primary group name | From cached snapshot |
| `%stonyperms_primary_group_display%` | Display name of primary group | Falls back to primary group name if missing |
| `%stonyperms_prefix%` | Active prefix | Empty string if absent |
| `%stonyperms_suffix%` | Active suffix | Empty string if absent |
| `%stonyperms_groups%` | Comma-joined group names | Ordered by snapshot weights (e.g. `admin,vip,default`) |
| `%stonyperms_weight%` | Highest inherited group weight | Integer string (`0` if empty) |
| `%stonyperms_meta_<key>%` | Metadata string for `<key>` | Case-preserved key; empty string if absent |
| `%stonyperms_in_group_<group>%` | `"true"` or `"false"` | Case-insensitive group membership check |
| `%stonyperms_has_permission_<node>%` | `"true"` or `"false"` | Evaluated via `checkCached == Tri.TRUE` |

### Vault `Permission` & `Chat` Semantics

- Registered at `ServicePriority.Normal` via `VaultRegistrar` when `Vault` is enabled and `integrations.vault: true`.
- **Reads:** `playerHas`, `getPrimaryGroup`, `getPlayerGroups`, `playerInGroup`, `getPlayerPrefix`/`Suffix`, `getPlayerInfo*`, `getGroupPrefix`/`Suffix`, and `getGroupInfo*` run non-blockingly against cached user snapshots and cached group definitions (`fetchAllGroups`).
- **Transient permissions:** `playerAddTransient` / `playerRemoveTransient` always return `false` (StonyPerms permissions live on Gate, not ephemeral local attachments).
- **Writes:** Mutating Vault `Permission` and `Chat` methods (`playerAdd`, `playerRemove`, `playerAddGroup`, `playerRemoveGroup`, `groupAdd`, `groupRemove`, `setPlayerPrefix`/`Suffix`, `setGroupPrefix`/`Suffix`, `setPlayerInfo*`, `setGroupInfo*`) **fail closed** (return `false` / no-op) unless `api.isWriteEnabled()` is `true`. When enabled, mutations dispatch asynchronously on the bridge I/O pool (using priority `100` for Vault prefix/suffix writes) and return `true` without stalling the main thread.
