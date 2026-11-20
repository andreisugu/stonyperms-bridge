# Limitations (first slice)

These are deliberate boundaries, not hidden behavior.

## Paper: no arbitrary on-demand permission hook

Bukkit's `Permissible.hasPermission(String)` is a map lookup. There is no
standard provider API that lets a plugin answer a node it has never seen.

The Paper adapter therefore materializes a finite universe:

- every `Permission` registered with `PluginManager` (including `plugin.yml`
  declarations, because plugins register them on load);
- every literal (non-wildcard) node in the StonyPerms snapshot.

Wildcards are expanded against that universe. Consequences:

- A plugin that checks an undeclared node will not see a StonyPerms wildcard.
  It sees its own default. Adding a literal StonyPerms node with that exact key
  makes it work; better, ask the plugin to declare its permissions.
- Bukkit expands registered parent permissions into their declared children
  during recalculation, and the overwrite order is not guaranteed. If a
  registered parent has a child that is not explicitly materialized with the
  same value, the parent is omitted from the attachment so an explicit child
  denial or an undefined child cannot be altered. A plugin checking that parent
  node itself may therefore see its default instead of the inherited grant.
- Permissions registered later (after the snapshot was applied) are picked up on
  the next refresh, which rebuilds the universe.
- This is the same fundamental constraint lightweight attachment-based
  permission bridges have. LuckPerms solves it by injecting a custom
  `Permissible`; that is version-coupled NMS/CraftBukkit work and is not in this
  slice.

## Fabric: only fabric-permissions-api consumers

The mod registers `PermissionCheckEvent`, `OptionRequestEvent`, and the offline
events. Mods that call `Permissions.check(...)` get correct wildcard/context
behavior. Mods that use raw vanilla permission levels do not.

Minecraft 26.3 introduced `net.minecraft.server.permissions.PermissionSet` and
`PermissionLevel`. A future mixin can replace `ServerPlayer.permissions()` and
map permission atoms/levels to StonyPerms nodes, but that is a separate,
version-sensitive change. It is intentionally not included.

Also, `fabric-permissions-api` uses the first non-`DEFAULT` listener. Running
two providers (for example LuckPerms Fabric plus this bridge) is undefined.
Use exactly one.

## Contexts

Snapshots are requested with `server` and `world` by default. Custom contexts
(region, gamemode, dimension-specific keys) are supported by the API but the
adapters only send those two. A plugin that needs a custom context must call
`/v1/check` (Paper `/spb check`) or the bridge would need a context-provider
extension.

A world change triggers a refetch, so world-scoped nodes work. During the
in-flight window the old snapshot is served; keep context-sensitive grants
idempotent or accept a short delay.

## Expiry boundaries

The proxy filters expired nodes at snapshot time and cleans them on a
one-minute ticker. A snapshot can contain a node that expires seconds later;
the client also filters by `expiry`, so checks are correct, but a cached
snapshot's `nodes` list may include a just-expired entry until refresh. The
resolver checks expiry on every evaluation.

## Prefix/suffix/meta

- Values are passed through verbatim, including legacy `&` color codes. The
  bridge does not translate them to Adventure components.
- Paper does not use prefix/suffix for chat by itself; another chat plugin must
  consume them.
- Metadata is a flat string map. There is no typed metadata.

## No write path

`/spb` cannot grant, deny, promote, or create groups. All mutations remain
`/sp` on the proxy. This keeps one writer and one audit log.

## One proxy, one token

v1 supports a single shared token and no key rotation overlap. Multi-proxy
deployments need one API per proxy or a future multi-token version.

## Ops and break-glass

With `apply-to-ops: true` (default), Paper ops also receive the StonyPerms
attachment; unmanaged nodes still fall back to vanilla op defaults. With
`apply-to-ops: false`, the bridge skips ops entirely, so they remain vanilla;
this does not make StonyPerms deny them. Console/vanilla-op break-glass remains
available in both modes.

Fabric `/spb` is always available to console and vanilla ops for the same
reason.

## No live rollout in this change

The repository, builds, and tests are staged. No Gate, Paper, or Fabric live
server was modified. Promotion and rollback are documented in
[`rollout.md`](rollout.md).
