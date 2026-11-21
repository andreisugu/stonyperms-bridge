# StonyPerms Bridge

Server-side permission adapters, live invalidation, developer API, and opt-in cross-server administration that keep **StonyPerms on Gate** as the single source of truth for Paper and Fabric backends.

- **Paper (`paper`)**: materializes StonyPerms nodes into a per-player Bukkit `PermissionAttachment`, registers optional `Vault` (`Permission`/`Chat`) and `PlaceholderAPI` (`%stonyperms_*%`) providers with `softdepend: [Vault, PlaceholderAPI]` (zero shading), and exposes `/spb`, `StonyPermsBridgeAPI` via Bukkit `ServicesManager`, and `StonyPermsUserUpdateEvent`.
- **Fabric (`fabric`)**: registers a [`fabric-permissions-api`](https://github.com/lucko/fabric-permissions-api) provider (`PermissionCheckEvent`, `OptionRequestEvent`, and offline variants), `/spb`, `StonyPermsBridgeMod.api()`, and `StonyPermsEvents.USER_UPDATED` / `CHANGE_RECEIVED`.
- **Core (`core`)**: JDK-only HTTP client (`StonyPermsClient`), SSE subscriber (`StonyPermsEventStream`), revision/ETag-aware `SnapshotCache`, fail-closed policy, Java port of StonyPerms' evaluation order (`PermissionResolver`), and `StonyPermsBridgeAPI`.

The proxy-side HTTP API lives in [`andreisugu/gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms) and is documented in [`docs/http-api.md`](https://github.com/andreisugu/gate-stonyperms/blob/main/docs/http-api.md).

## Architecture (v0.2)

```text
Gate + gate-stonyperms (single source of truth)
        │  Bearer HTTP/JSON + GET /v1/events SSE (read tier)
        │  Opt-in write tier (write.enabled: false by default)
        ├──────────────► Paper plugin ──► PermissionAttachment / Vault / PAPI / ServicesManager
        └──────────────► Fabric mod  ──► fabric-permissions-api / StonyPermsBridgeMod.api()
```

- **Near-real-time propagation:** When `sse.enabled: true` (default), backends subscribe to `GET /v1/events` (`text/event-stream`) with exponential backoff (`1000ms`–`30000ms`), refreshing changed online players immediately while keeping `ETag` / `If-None-Match` polling (`refresh-seconds: 30`) as a self-healing fallback.
- **Opt-in write capability:** Disabled (`write.enabled: false`) by default. Backends granted a separate write-tier token (`STONYPERMS_BRIDGE_WRITE_TOKEN`, `api.write-token`, or `api.write-token-file`) can run mutating `/spb user|group|track`, `/spb log`, and `/spb reload remote` commands and call `StonyPermsBridgeAPI` / Vault write methods.

## Modules

| Module | Output | Purpose |
| --- | --- | --- |
| `core` | library / nested Fabric jar (`include(project(":core"))`) | HTTP client, SSE subscriber, snapshot cache, resolver, `StonyPermsBridgeAPI`, `/spb` command engine |
| `paper` | `stonyperms-bridge-paper-*.jar` | Paper plugin, attachment materializer, Vault, PlaceholderAPI, `/spb` |
| `fabric` | `stonyperms-bridge-fabric-*.jar` | Fabric mod (`fabric-permissions-api`), `/spb`, Fabric events |

## Quick start

1. On the Gate host, enable the read API (and optionally the write tier with a **distinct** token file):

   ```toml
   # config/stonyperms.toml
   api_enabled = true
   api_listen = "127.0.0.1:8737"
   api_token_file = "config/stonyperms-api.token"
   api_sse_enabled = true
   # Optional:
   # api_write_enabled = true
   # api_write_token_file = "config/stonyperms-api-write.token"
   ```

   ```bash
   umask 077
   openssl rand -hex 32 > config/stonyperms-api.token
   chmod 600 config/stonyperms-api.token
   ```

2. Verify the proxy API:

   ```bash
   curl -s -H "Authorization: Bearer $(cat config/stonyperms-api.token)" \
     http://127.0.0.1:8737/v1/health
   ```

3. Install the backend adapter and give it a **single-line** `0600` read token file via `STONYPERMS_BRIDGE_TOKEN`, `api.token`, or `api.token-file` (`tokenFile` on Fabric). Set `server-name` (`serverName` on Fabric) to match the Gate backend name used in StonyPerms contexts.

Full setup: [`docs/install.md`](docs/install.md) | Chaonius workflows & developer API: [`docs/integrations.md`](docs/integrations.md).

## Build

Requires JDK 25+ and network access to Maven Central, PaperMC, and Fabric.

```bash
./gradlew build
```

Outputs:

- `paper/build/libs/stonyperms-bridge-paper-0.2.0-SNAPSHOT.jar`
- `fabric/build/libs/stonyperms-bridge-fabric-0.2.0-SNAPSHOT.jar`

Run tests:

```bash
./gradlew test
```

## Commands

Both platforms expose `/spb` with two distinct permissions (both default `op`):

- **`stonyperms.bridge.admin`** — `status`, `refresh [player]`, `check <player> <permission>`, `reload` (local bridge reload), `user <player> info`, `group list|<name> info|members`, `track list|<name> info`.
- **`stonyperms.bridge.write`** (additionally requires `write.enabled: true` and a write token) — `user <player> <group|parent|primarygroup|permission|meta|promote|demote|clear> ...`, `group <name> <create|delete|setweight|setdisplayname|permission|parent|meta|prefix|suffix> ...`, `track <name> <create|delete|set|append|remove> ...`, `log [limit] [targetType=] [target=] [actor=]`, and `reload remote`.

`/spb check` asks the proxy directly (`POST /v1/check`); cached gameplay checks read the local snapshot cache.

## Failure behavior

- API unreachable with no cache: `fail-closed: true` denies StonyPerms-managed permissions; `false` falls back to platform defaults.
- API unreachable with a cached snapshot: served until `stale-grace-seconds`, then denied (or platform defaults if `fail-closed` is off).
- An explicit `undefined` evaluation means StonyPerms has no opinion, so the platform default applies.
- Operators are managed by StonyPerms by default (`apply-to-ops: true` on both Paper and Fabric); set `false` to skip ops entirely so they stay vanilla/standard-op.
- Missing or disabled write configuration fails closed before issuing HTTP writes (`403 writes_disabled` / `403 insufficient_scope` on the proxy as well).

## Known limitations

See [`docs/limitations.md`](docs/limitations.md). The key platform boundaries remain: Bukkit lacks an on-demand permission hook (Paper materializes registered Bukkit permissions plus literal snapshot nodes), Fabric intercepts `fabric-permissions-api` consumers rather than raw vanilla permission levels, and bridges currently expect single-line token files.

## Documentation

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/api-contract.md`](docs/api-contract.md)
- [`docs/integrations.md`](docs/integrations.md)
- [`docs/install.md`](docs/install.md)
- [`docs/security.md`](docs/security.md)
- [`docs/limitations.md`](docs/limitations.md)
- [`docs/rollout.md`](docs/rollout.md)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
