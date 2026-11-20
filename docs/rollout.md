# Rollout plan

No live changes are part of the PRs. This is the staged path for the owner.

## Phase 0 — review only

- Review `gate-stonyperms#1` (API) and `stonyperms-bridge#1` (adapters).
- Confirm the API contract and the resolver ordering against existing
  `/sp check` behavior on a staging Gate.
- Confirm no secrets are committed.

## Phase 1 — proxy API, dark launch

On a staging Gate:

1. Deploy the API-enabled StonyPerms build with `api_enabled = false` first;
   verify normal Gate behavior and existing `/sp` commands.
2. Enable the API on `127.0.0.1:8737`, create a token, restart.
3. From the Gate host, verify `/v1/health`, `/v1/revision`, a known user
   snapshot, and `/v1/check` against `/sp check`.
4. Compare snapshots for:
   - a default user,
   - a user with direct nodes,
   - a user in multiple groups with different weights,
   - a temporary node,
   - a context-scoped node (`server=lobby` vs `hub`).
5. Check that the token never appears in logs and that `/healthz` is the only
   unauthenticated route.

Rollback: set `api_enabled = false` and restart Gate. No data migration exists.

## Phase 2 — one Paper staging server

1. Install the Paper jar on a non-production backend.
2. Point it at the staging Gate API; set `fail-closed: false` and
   `apply-to-ops: false` for the first boot to avoid locking anyone out.
3. Verify `/spb status`, `/spb check`, and that no permissions changed
   unexpectedly.
4. Compare a sample of `player.hasPermission(...)` results (via a test plugin
   or existing commands) with `/sp check` on the proxy.
5. Switch `fail-closed: true`, keep `apply-to-ops` as appropriate, and restart.
6. Test the failure path: stop the API (or firewall the port), confirm cached
   snapshots serve during the grace window and grants disappear afterward.
7. Test recovery: start the API, `/spb refresh`, confirm grants return.

Rollback: remove the plugin jar, restart Paper. Bukkit attachments are removed
on disable; no persistent state is written by the bridge.

## Phase 3 — one Fabric staging server

1. Install Fabric API + fabric-permissions-api 0.7.0 + the bridge jar.
2. Configure a different `serverName` matching Gate's backend name.
3. Verify a mod that uses `Permissions.check` (or a small test mod) sees
   StonyPerms values, including a wildcard and a context-scoped node.
4. Verify `/spb status` on console and as an op.
5. Repeat the failure/recovery test.
6. Confirm no other `PermissionCheckEvent` provider is installed.

Rollback: remove the mod jar; no persistent state.

## Phase 4 — production canary

- Pick one low-risk backend (lobby or a staging-like server).
- Keep the proxy API loopback/tailnet-only.
- Run for at least one full cache/stale cycle and one deploy/restart.
- Watch:
  - backend logs for refresh failures (rate-limited),
  - `/spb status` cache size and last error,
  - proxy logs for API errors,
  - player reports of missing/extra permissions.
- Only then roll to the remaining backends, one at a time.

## Phase 5 — hardening / follow-ups

- Paper: evaluate a version-pinned `Permissible` injector to remove the
  declared-universe limitation, behind a feature flag.
- Fabric: add a mixin for `ServerPlayer.permissions()` /
  `PermissionLevel` mapping, behind a feature flag.
- API: multi-token rotation, metrics, optional structured audit of API access.
- Add a shadow/observe mode that logs would-be decisions before enforcing.

## Acceptance criteria

- `/sp check` and `/spb check` agree for a matrix of users, groups, wildcards,
  negations, contexts, and temporary nodes.
- A group change on Gate is visible on the backend within
  `refresh-seconds` (or one `/spb refresh`).
- API outage behavior matches the configured fail-closed/stale policy.
- No token appears in logs, config committed to git, or API responses.
- Rollback requires only removing the backend adapter or disabling the API;
  no data cleanup.

## Open decisions for the owner

- Is `fail-closed: true` acceptable for the first production backend, or should
  the first canary run with `false` and a short stale grace?
- Should Paper ops be fully managed by StonyPerms (`apply-to-ops: false`) or
  keep vanilla op defaults as break-glass?
- Which backend name(s) must be used in `server-name` to match existing
  StonyPerms contexts?
- Is a tailnet address stable enough for backend-to-Gate traffic, or is a TLS
  reverse proxy preferred?
