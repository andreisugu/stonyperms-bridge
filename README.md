# StonyPerms Bridge

Server-side permission adapters that make **StonyPerms on Gate** the source of
truth for Paper and Fabric backends.

- **Paper**: materializes StonyPerms nodes into a per-player Bukkit
  `PermissionAttachment`.
- **Fabric**: registers a provider for
  [`fabric-permissions-api`](https://github.com/lucko/fabric-permissions-api),
  so `Permissions.check(...)` and `Permissions.getPermissionValue(...)` answer
  from the proxy.
- **Core**: JDK-only HTTP client, revision/ETag-aware snapshot cache,
  fail-closed policy, and a Java port of StonyPerms' evaluation order.

The proxy-side API lives in
[`andreisugu/gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms)
and is documented in
[`docs/http-api.md`](https://github.com/andreisugu/gate-stonyperms/blob/main/docs/http-api.md).

## Architecture in one diagram

```text
Gate + StonyPerms (source of truth)
        │  read-only HTTP/JSON, bearer token, loopback or tailnet
        ├──────────────► Paper plugin ──► Bukkit PermissionAttachment
        └──────────────► Fabric mod  ──► fabric-permissions-api event
```

The bridge never writes permissions, never reads StonyPerms storage, and never
opens a database connection. The proxy remains the only writer.

## Modules

| Module | Output | Purpose |
| --- | --- | --- |
| `core` | library / nested Fabric jar | API client, snapshot model, cache, resolver |
| `paper` | `stonyperms-bridge-paper-*.jar` | Paper plugin + `/spb` admin command |
| `fabric` | `stonyperms-bridge-fabric-*.jar` | Fabric mod + `/spb` admin command |

## Quick start

1. On the Gate host, enable the API (it is off by default):

   ```toml
   # config/stonyperms.toml
   api_enabled = true
   api_listen = "127.0.0.1:8737"
   api_token_file = "config/stonyperms-api.token"
   ```

   ```bash
   openssl rand -hex 32 > config/stonyperms-api.token
   chmod 600 config/stonyperms-api.token
   ```

2. Verify it:

   ```bash
   curl -s -H "Authorization: Bearer $(cat config/stonyperms-api.token)" \
     http://127.0.0.1:8737/v1/health
   ```

3. Install the backend adapter and give it the same token via
   `STONYPERMS_BRIDGE_TOKEN`, `api.token`, or `api.token-file`. Set
   `server-name` to the Gate backend name used by StonyPerms contexts.

Full instructions: [`docs/install.md`](docs/install.md).

## Build

Requires JDK 25+ and network access to Maven Central, PaperMC, and Fabric.

```bash
./gradlew build
```

Outputs:

- `paper/build/libs/stonyperms-bridge-paper-0.1.0-SNAPSHOT.jar`
- `fabric/build/libs/stonyperms-bridge-fabric-0.1.0-SNAPSHOT.jar`

Run tests:

```bash
./gradlew test
```

## Commands

Both platforms expose `/spb` (permission `stonyperms.bridge.admin`, default op):

```text
/spb status
/spb refresh [player]
/spb check <player> <permission>
/spb reload
```

`/spb check` asks the proxy for an authoritative answer; the others use the
local snapshot cache.

## Failure behavior

- API unreachable with no cache: `fail-closed: true` denies StonyPerms-managed
  permissions; `false` falls back to platform defaults.
- API unreachable with a cached snapshot: served until
  `stale-grace-seconds`, then denied (or platform defaults if fail-closed is
  off).
- A defined `undefined` answer means StonyPerms has no opinion, so the platform
  default applies. That is different from "the bridge has no data".
- Paper operators are managed by StonyPerms by default (`apply-to-ops: true`); set it to `false` to leave ops fully vanilla.

## Known limitations

See [`docs/limitations.md`](docs/limitations.md). The important one: Bukkit has
no on-demand permission hook, so Paper only materializes the **known** Bukkit
permission universe plus literal snapshot nodes. Fabric gets true on-demand
checks through `fabric-permissions-api`, but mods that never call that API (or
vanilla permission levels) are not covered yet.

## Documentation

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/api-contract.md`](docs/api-contract.md)
- [`docs/install.md`](docs/install.md)
- [`docs/security.md`](docs/security.md)
- [`docs/limitations.md`](docs/limitations.md)
- [`docs/rollout.md`](docs/rollout.md)

## License

Apache-2.0. See [`LICENSE`](LICENSE).
