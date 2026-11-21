# Limitations & Operational Boundaries (v0.2)

These are deliberate design boundaries in `stonyperms-bridge` `0.2.0` and `gate-stonyperms`.

## Paper: no arbitrary on-demand permission hook

Bukkit's `Permissible.hasPermission(String)` is a map lookup rather than a callback hook. The Paper adapter materializes a finite permission universe:

- every `Permission` registered with `PluginManager` (including `plugin.yml` declarations);
- every literal (non-wildcard) node in the player's StonyPerms snapshot.

Wildcards are expanded against that declared universe:

- Undeclared nodes checked by plugins will not match a StonyPerms wildcard unless the plugin registers the node in `plugin.yml` or a matching literal node exists in StonyPerms.
- If a registered Bukkit parent permission has a declared child not explicitly materialized with the same boolean value, the parent node is omitted from the attachment so child denials or undefined children are not overridden by Bukkit recalculation.
- Dynamically registered permissions appear on the next SSE-triggered or scheduled refresh.

## Fabric: `fabric-permissions-api` scope & provider exclusivity

- Listeners cover `PermissionCheckEvent`, `OptionRequestEvent`, `OfflinePermissionCheckEvent`, and `OfflineOptionRequestEvent`. Mods relying purely on raw vanilla permission levels without `fabric-permissions-api` are not intercepted (intentionally avoiding fragile NMS/mixin `ServerPlayer.permissions()` overrides).
- `fabric-permissions-api` uses the first non-`DEFAULT` listener; never run a second permission provider (such as LuckPerms Fabric) alongside the bridge.

## Single-line bridge token files

`gate-stonyperms` (`LoadTokens`) supports up to 4 newline-separated tokens and `#` comments per tier for zero-downtime key rotation, **but Paper (`PaperBridgeConfig`) and Fabric (`FabricBridgeConfig`) read token files as a single trimmed string**. Give each bridge backend a dedicated single-token `0600` file; never deploy a multi-line rotation file directly to a Paper or Fabric host.

## Write path & idempotency boundaries

- Writes are **opt-in and fail closed** (`write.enabled = false` by default). Backends without a distinct write token never issue write requests.
- **Vault async writes:** When `write.enabled = true`, Vault `Permission` and `Chat` mutating calls dispatch asynchronously via `CompletableFuture` on the bridge I/O pool and return `true` immediately without blocking the server main thread; they return `false` when writes are disabled. Group list reads in `StonyPermsVaultPermission` rely on a cached snapshot from `fetchAllGroups`.
- **In-memory idempotency window:** Gate's `Idempotency-Key` store is bounded in-memory (`4096` entries, `15m` TTL) and resets on Gate restart. External callers such as store fulfillment agents must retain their own durable order ledger and treat idempotency keys as short-window retry protection rather than permanent storage.
- **No silent offline UUID creation on path mutations:** Writing by username to `/v1/users/{id}/...` fails with `404 user_not_found` if Gate has never seen that player. Programmatic callers provisioning brand-new offline/pre-join buyers must call `ensureUser(..., createOffline=true)` (`POST /v1/users`) or pass a verified Mojang UUID first.

## Contexts

- Adapters automatically attach `server` (when `server-name` / `serverName` is non-empty) and `world`. Plugins or external callers needing custom context keys (region, gamemode, etc.) must pass them explicitly to `StonyPermsBridgeAPI` / `POST /v1/check` / HTTP endpoints.
- During a world-change refresh, the prior world's snapshot briefly serves until the async fetch returns.

## Expiry boundaries & SSE propagation

- SSE (`GET /v1/events`) pushes changes immediately when connected, dropping events only if a single slow subscriber fills its 64-event proxy buffer. Periodic polling (`refresh-seconds = 30`) with `If-None-Match` (`304 Not Modified`) heals any missed frame.
- Proxy `expiry_cleanup` ticks once per minute; the Java `PermissionResolver` also checks `expiry` locally at evaluation time so expired nodes stop granting immediately even before the next refresh tick.

## Prefix, suffix, and metadata formatting

- Values pass through verbatim (`&` codes, MiniMessage tags, etc.) without format translation.
- Metadata is a flat `Map<String, String>`. Vault `Chat` number/boolean `InfoNode` queries parse cleanly from strings and write string values, with `DEFAULT_VAULT_AFFIX_PRIORITY = 100` for Vault prefix/suffix writes.

## Ops and break-glass

- `apply-to-ops: true` (default) applies StonyPerms permissions to operators on both Paper and Fabric. Setting `apply-to-ops: false` skips ops (`Paper` skips attachment application; `Fabric` `isOpBypassed` yields `TriState.DEFAULT`), leaving them vanilla without denying them.
- Fabric console and `ops.json` ops retain break-glass `/spb` access in both modes.
