<div align="center">

# 🌉 StonyPerms Bridge

**Dual-mode permissions manager & Paper/Fabric adapter for Minecraft 26.3 — run synced with Gate via HTTP + live SSE (`bridge`), or self-contained with JSON, SQLite, MariaDB/MySQL, and PostgreSQL (`standalone`).**

<p>
  <a href="https://github.com/andreisugu/stonyperms-bridge/actions/workflows/ci.yml"><img src="https://github.com/andreisugu/stonyperms-bridge/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/andreisugu/stonyperms-bridge/releases"><img src="https://img.shields.io/github/v/release/andreisugu/stonyperms-bridge?style=flat-square&label=Release" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25" />
  <img src="https://img.shields.io/badge/Platform-Paper_%26_Fabric_26.3-7c3aed?style=flat-square" alt="Paper & Fabric 26.3" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square" alt="Apache 2.0" /></a>
</p>

<p>
  <a href="#-two-operating-modes">Modes</a> •
  <a href="#-highlights">Highlights</a> •
  <a href="#-storage-backends-standalone-mode">Storage Backends</a> •
  <a href="#-bridge-mode--quick-start">Quick Start</a> •
  <a href="#-chat-tablist--placeholderapi">Chat & Placeholders</a> •
  <a href="#-commands--permissions">Commands</a> •
  <a href="#-failure-behavior--limitations">Limitations</a> •
  <a href="#-build-from-source">Build</a> •
  <a href="#-documentation">Docs</a> •
  <a href="#-minekube-gate-extensions-suite">Gate Suite</a>
</p>

</div>

---

## 🔀 Two Operating Modes

Choose per backend whether `stonyperms-bridge` mirrors [`gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms) over HTTP + SSE or runs standalone directly inside Paper / Fabric:

| Aspect | `mode: "bridge"` *(default)* | `mode: "standalone"` |
| :--- | :--- | :--- |
| **Source of truth** | Gate proxy (`gate-stonyperms` `v0.3.0`) | Local backend (`json`, `sqlite`, `mariadb`/`mysql`, or `postgresql`) |
| **Network dependency** | Scoped Bearer HTTP + live `GET /v1/events` SSE | Zero Gate / HTTP requirement |
| **Evaluation parity** | Proxy evaluates; bridge caches & materializes snapshots | Identical Java `LocalPermissionEngine` verified against `spec/` (`SHA256SUMS` in CI) |
| **Commands exposed** | `/spb` *(never claims `/sp` so Gate keeps `/sp`)* | `/spb`, `/sp`, and `/stonyperms` |
| **Write control** | Opt-in (`write.enabled: true` + distinct write token) | Enabled by default unless `write.enabled: false` |

---

## ✨ Highlights

- **Paper (`paper`):** Materializes resolved nodes into per-player Bukkit `PermissionAttachment`s, registers optional `Vault` (`Permission` / `Chat`) and `PlaceholderAPI` (`%stonyperms_*%`) providers via `softdepend: [Vault, PlaceholderAPI]` (zero shading), formats chat/tablist display names with network nickname support, exposes `StonyPermsBridgeAPI` on Bukkit `ServicesManager`, and fires `StonyPermsUserUpdateEvent`. Shadowed Paper releases exclude `org/slf4j/**` and merge `META-INF/services/java.sql.Driver` for all SQL backends.
- **Fabric (`fabric`):** Registers a [`fabric-permissions-api`](https://github.com/lucko/fabric-permissions-api) provider answering online, offline, and Permission API v1 identifier checks from cached/standalone snapshots, resends the Brigadier command tree when snapshots arrive after join, applies chat & display formatting, bundles runtime libraries cleanly via Jar-in-Jar, and exposes `StonyPermsBridgeMod.api()` + `StonyPermsEvents.USER_UPDATED` / `CHANGE_RECEIVED`.
- **Core (`core`):**
  - Authoritative standalone engine (`LocalPermissionEngine`, `LocalPermissionManager`) with RE2J `1.8` (`r=...` regex), shorthand expansion (`{a,b}`, `(a|b)`, `{1-N}`), context-specificity tie-breaking, temporary modifiers (`accumulate`, `replace`, `deny`), `primary-group-calculation`, prefix/suffix stacking, bulk updates, search, and `sp1_` portable `PortableWebCodec` URLs for [StonyPermsWeb](https://andreisugu.github.io/gate-stonyperms/).
  - Dual persistence (`JsonLocalStorage` + `HikariCP 7.1.0` pooled `SqlLocalStorage`) conforming strictly to the canonical Go↔Java conformance spec (`core/src/test/resources/spec/`).
  - JDK-only HTTP client (`StonyPermsClient`), SSE subscriber (`StonyPermsEventStream`), revision/ETag-aware `SnapshotCache`, and `StonyPermsBridgeAPI`.

---

## 🗄️ Storage Backends (Standalone Mode)

Standalone mode supports flat-file **`json`** (default) plus **HikariCP-pooled** relational SQL databases using the exact same schema v1 (`spec/SPEC.md` §10) as [`gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms):

| Storage Type | Driver Bundled | Key Behavior |
| :--- | :--- | :--- |
| **`json`** *(default)* | Atomic JSON (`Gson`) | Reads/writes `groups.json`, `tracks.json`, `logs.json`, and `users/<uuid>.json` directly compatible with `gate-stonyperms`. |
| **`sqlite`** / `sqlite3` | `org.xerial:sqlite-jdbc:3.54.1.0` | Embedded single-file database (`<storage-dir>/stonyperms.db` by default or `database.file`), WAL journal mode, foreign keys enforced. |
| **`mariadb`** / **`mysql`** | `org.mariadb.jdbc:mariadb-java-client:4.0.0` | Connects via MariaDB/MySQL wire protocol; uses InnoDB + `utf8mb4_bin` collation on `meta_key` columns to preserve exact key casing. |
| **`postgresql`** / `postgres` | `org.postgresql:postgresql:42.7.14` | Full PostgreSQL support with `BIGSERIAL` log IDs, `ON CONFLICT` upserts, and standard `sslMode` mapping (`disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`). |

### Credential Resolution Order
In `standalone` SQL mode, passwords resolve in priority order:
1. `STONYPERMS_BRIDGE_DB_PASSWORD` environment variable
2. `STONYPERMS_DB_PASSWORD` environment variable
3. Inline `database.password`
4. `database.password-file` (Paper) / `database.passwordFile` (Fabric), trimmed of trailing CR/LF

**Paper (`plugins/StonyPermsBridge/config.yml`):**
```yaml
mode: "standalone"
standalone:
  storage-type: "sqlite" # json | sqlite | mysql | mariadb | postgresql
  storage-dir: "data"
  database:
    file: ""             # Optional SQLite file override
    host: "127.0.0.1"
    port: 0              # 0 uses engine default (3306 for mysql/mariadb, 5432 for postgresql)
    database: "stonyperms"
    username: "stonyperms"
    password: ""
    password-file: ""    # Prefer env var or password-file over inline password
    table-prefix: "stonyperms_"
    ssl-mode: "prefer"
  default-group: "default"
  primary-group-calculation: "stored"
```

**Fabric (`config/stonyperms-bridge.json`):**
```json
{
  "mode": "standalone",
  "standalone": {
    "storageType": "sqlite",
    "storageDir": "stonyperms/data",
    "database": {
      "host": "127.0.0.1",
      "port": 0,
      "database": "stonyperms",
      "username": "stonyperms",
      "passwordFile": "",
      "tablePrefix": "stonyperms_",
      "sslMode": "prefer"
    },
    "primaryGroupCalculation": "stored"
  }
}
```

---

## 🌉 Bridge Mode & Quick Start

```text
Gate + gate-stonyperms (single source of truth)
        │  Bearer HTTP/JSON + GET /v1/events SSE (read tier)
        │  Opt-in write tier (write.enabled: false by default)
        ├──────────────► Paper plugin ──► PermissionAttachment / Vault / PAPI / ServicesManager
        └──────────────► Fabric mod  ──► fabric-permissions-api / StonyPermsBridgeMod.api()
```

### Releases & Downloads
Download release jars (`stonyperms-bridge-paper-<version>.jar`, `stonyperms-bridge-fabric-<version>.jar`) and `SHA256SUMS` from [GitHub Releases](https://github.com/andreisugu/stonyperms-bridge/releases):
- **Paper:** Drop `stonyperms-bridge-paper-0.2.0.jar` into `plugins/`.
- **Fabric:** Drop `stonyperms-bridge-fabric-0.2.0.jar` into `mods/` alongside Fabric Loader `>=0.20.0`, Fabric API, and `fabric-permissions-api`.

### Connecting to Gate (`mode: "bridge"`)
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

3. Supply the **single-line** `0600` read token on each backend via `STONYPERMS_BRIDGE_TOKEN`, `api.token`, or `api.token-file` (`tokenFile` on Fabric). Set `server-name` (`serverName` on Fabric) to match the Gate backend name used in StonyPerms contexts.

- **Near-real-time propagation:** With `sse.enabled: true` (default), backends subscribe to `GET /v1/events` (`text/event-stream`) with exponential backoff (`1000ms`–`30000ms`), while `ETag` / `If-None-Match` polling (`refresh-seconds: 30`) provides self-healing fallback.
- **Opt-in write capability:** Disabled (`write.enabled: false`) by default in `bridge` mode. Supplying a separate write token (`STONYPERMS_BRIDGE_WRITE_TOKEN`, `api.write-token`, `api.write-token-file`) unlocks `/spb` mutations, `/spb log`, `/spb reload remote`, `StonyPermsBridgeAPI` writes, and Vault write calls.

### Modules

| Module | Artifact | Purpose |
| :--- | :--- | :--- |
| `core` | library / nested Fabric jar | Engine, JSON + SQL storage, HTTP client, SSE subscriber, cache, `StonyPermsBridgeAPI`, CLI engine |
| `paper` | `stonyperms-bridge-paper-*.jar` | Paper plugin, attachment materializer, Vault, PlaceholderAPI, chat/tablist, `/spb` (+ standalone `/sp`) |
| `fabric` | `stonyperms-bridge-fabric-*.jar` | Fabric mod (`fabric-permissions-api`), chat/display formatting, Fabric events, `/spb` (+ standalone `/sp`) |

---

## 💬 Chat, Tablist & PlaceholderAPI

- **Chat & Display Formatting:** Enabled by default (`chat.enabled: true`, `format: "{prefix}{name}{suffix}&r&7: &f{message}"` on both Paper and Fabric; `tablist.enabled: true` on Paper; `display.enabled: true` on Fabric) and respects synced network nicknames (`meta.nickname` / `meta.nick`).
- **PlaceholderAPI (Paper):**

| Placeholder | Returns |
| :--- | :--- |
| `%stonyperms_primary_group%` / `%stonyperms_primary_group_name%` | Resolved primary group key |
| `%stonyperms_primary_group_display%` | Primary group display name |
| `%stonyperms_prefix%` / `%stonyperms_suffix%` | Effective prefix / suffix |
| `%stonyperms_groups%` / `%stonyperms_inherited_groups%` | Comma-separated inherited group keys |
| `%stonyperms_highest_group_by_weight%` / `%stonyperms_lowest_group_by_weight%` | Highest / lowest weighted group key |
| `%stonyperms_weight%` | Effective weight of highest weighted group |
| `%stonyperms_meta_<key>%` | Metadata string for `<key>` |
| `%stonyperms_in_group_<group>%` / `%stonyperms_inherits_group_<group>%` | `true` / `false` membership check |
| `%stonyperms_has_permission_<node>%` / `%stonyperms_check_permission_<node>%` | Boolean check / tri-state (`true`/`false`/`undefined`) |
| `%stonyperms_expiry_time_<perm>%` | Remaining duration for temporary permission `<perm>` |
| `%stonyperms_context_<key>%` | Active context value for `<key>` on the backend |

---

## ⌨️ Commands & Permissions

- **`stonyperms.bridge.admin`** *(default `op`)* — `status`, `info`, `refresh [player]`, `check <player> <permission>`, `search <query> [page]`, `reload`, `user <player> <info|permission info|parent info>`, `group list|<name> <info|members|permission info|parent info>`, `track list|<name> info`.
- **`stonyperms.bridge.write`** *(default `op`; requires write enabled)* — Full `user`, `group`, `track`, `bulkupdate`, `sync`/`networksync`, `editor [filter]`, `applyedits <code>`, `tree [root]`, `verbose paste`, `log [limit] [targetType=] [target=] [actor=]`, and `reload remote`.
- **`stonyperms.admin` / `stonyperms.*`** *(standalone mode)* — Grants access to `/sp`, `/stonyperms`, and `/spb` without requiring proxy-style bridge nodes.

> Running `/spb editor` (or `/sp editor` in standalone mode) on Paper collects registered plugin permission nodes from `Bukkit.getPluginManager().getPermissions()` and live world names automatically, opening [StonyPermsWeb](https://andreisugu.github.io/gate-stonyperms/) with clickable Adventure URLs in chat.

---

## 🛡️ Failure Behavior & Limitations

- In `bridge` mode when Gate is unreachable with no cached snapshot: `fail-closed: true` denies StonyPerms-managed permissions; `false` falls back to platform defaults.
- When Gate is unreachable with a valid cached snapshot: served up to `stale-grace-seconds`, then revoked (or platform defaults if `fail-closed` is off).
- An explicit `undefined` evaluation means StonyPerms has no opinion, so vanilla/platform defaults apply.
- Operators are managed by StonyPerms by default (`apply-to-ops: true`); set `false` to leave ops vanilla.

See [`docs/limitations.md`](docs/limitations.md) for platform boundaries.

---

## 🛠️ Build from Source

Requires JDK 25+ and network access to Maven Central, PaperMC, and Fabric.

```bash
./gradlew clean build check
# Release jars without -SNAPSHOT suffix:
./gradlew clean build -PreleaseVersion=0.2.0
```

Outputs:
- `paper/build/libs/stonyperms-bridge-paper-0.2.0.jar`
- `fabric/build/libs/stonyperms-bridge-fabric-0.2.0.jar`

---

## 📚 Documentation

- [`docs/architecture.md`](docs/architecture.md)
- [`docs/api-contract.md`](docs/api-contract.md)
- [`docs/integrations.md`](docs/integrations.md)
- [`docs/install.md`](docs/install.md)
- [`docs/security.md`](docs/security.md)
- [`docs/limitations.md`](docs/limitations.md)
- [`docs/rollout.md`](docs/rollout.md)

---

## 🌐 Minekube Gate Extensions Suite

| Project | Role | Stack |
| :--- | :--- | :--- |
| [`gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms) | Context-aware permissions, SQL/JSON storage, Web UI & v0.2 HTTP + SSE API | `Go 1.26` |
| **[`stonyperms-bridge`](https://github.com/andreisugu/stonyperms-bridge)** *(this repo)* | Paper & Fabric standalone/bridge permissions, SQL/JSON, `/spb` & `/sp`, Vault & PAPI | `Java 25` |
| [`gate-bettertab`](https://github.com/andreisugu/gate-bettertab) | Animated RGB / MiniMessage TAB lists, 1.21.2+ sorting, `/nick` & StonyPerms roles | `Go 1.26` |
| [`gate-discord4gate`](https://github.com/andreisugu/gate-discord4gate) | Bidirectional Discord <> Minecraft chat, slash commands & webhook avatars | `Go 1.26` |
| [`gate-hybridforwarding`](https://github.com/andreisugu/gate-hybridforwarding) | Per-backend Velocity Modern, Legacy BungeeCord, BungeeGuard & None forwarding | `Go 1.26` |
| [`gate-simplewhitelist`](https://github.com/andreisugu/gate-simplewhitelist) | Lock-free `atomic.Pointer` whitelist by username, UUID, IP & CIDR | `Go 1.26` |
| [`gate-skinschanger`](https://github.com/andreisugu/gate-skinschanger) | Resilient multi-provider & Mineskin URL skin switcher with live backend refresh | `Go 1.26` |
| [`gate-smartlimbo`](https://github.com/andreisugu/gate-smartlimbo) | Limbo failover, protected sandbox & batched backend auto-reconnect queues | `Go 1.26` |
| [`gate-plugin-builder`](https://github.com/andreisugu/gate-plugin-builder) | Zero-config static Gate bundler (`build.sh`) + Pelican / Pterodactyl egg | `Shell` |

---

## 📄 License

Licensed under the [Apache License 2.0](LICENSE) © Andrei.
