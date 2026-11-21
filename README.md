<div align="center">

# 🌉 StonyPerms Bridge

**Real-time Paper & Fabric permission adapters, developer API, and opt-in cross-server administration backed by StonyPerms on Gate.**

<p>
  <a href="https://github.com/andreisugu/stonyperms-bridge/actions/workflows/ci.yml"><img src="https://github.com/andreisugu/stonyperms-bridge/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  <a href="https://github.com/andreisugu/stonyperms-bridge/releases"><img src="https://img.shields.io/github/v/release/andreisugu/stonyperms-bridge?style=flat-square&label=Release" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/Java-25-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 25" />
  <img src="https://img.shields.io/badge/Platform-Paper_%26_Fabric_26.3-7c3aed?style=flat-square" alt="Paper & Fabric 26.3" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache_2.0-blue.svg?style=flat-square" alt="Apache 2.0" /></a>
</p>

<p>
  <a href="#-architecture-v02">Architecture</a> •
  <a href="#-quick-start">Quick Start</a> •
  <a href="#-build">Build</a> •
  <a href="#-commands">Commands</a> •
  <a href="#-documentation">Documentation</a> •
  <a href="#-minekube-gate-extensions-suite">Gate Suite</a>
</p>

</div>

---

## ✨ Highlights

- **Paper (`paper`):** Materializes StonyPerms nodes into a per-player Bukkit `PermissionAttachment`, registers optional `Vault` (`Permission`/`Chat`) and `PlaceholderAPI` (`%stonyperms_*%`) providers with `softdepend: [Vault, PlaceholderAPI]` (zero shading), and exposes `/spb`, `StonyPermsBridgeAPI` via Bukkit `ServicesManager`, and `StonyPermsUserUpdateEvent`.
- **Fabric (`fabric`):** Registers a [`fabric-permissions-api`](https://github.com/lucko/fabric-permissions-api) provider (`PermissionCheckEvent`, `OptionRequestEvent`, and offline variants), `/spb`, `StonyPermsBridgeMod.api()`, and `StonyPermsEvents.USER_UPDATED` / `CHANGE_RECEIVED`.
- **Core (`core`):** JDK-only HTTP client (`StonyPermsClient`), SSE subscriber (`StonyPermsEventStream`), revision/ETag-aware `SnapshotCache`, fail-closed policy, Java port of StonyPerms' evaluation order (`PermissionResolver` with Shorthand (`{a,b}`, `(a|b)`, `{1-N}`), regex `r=...`, and context-specificity tie-breaking), and `StonyPermsBridgeAPI`.
- **Placeholders (Paper):** `%stonyperms_primary_group%` / `%stonyperms_primary_group_name%`, `%stonyperms_primary_group_display%`, `%stonyperms_prefix%`, `%stonyperms_suffix%`, `%stonyperms_groups%` / `%stonyperms_inherited_groups%`, `%stonyperms_highest_group_by_weight%`, `%stonyperms_lowest_group_by_weight%`, `%stonyperms_weight%`, `%stonyperms_meta_<key>%`, `%stonyperms_in_group_<group>%` / `%stonyperms_inherits_group_<group>%`, `%stonyperms_has_permission_<node>%`, `%stonyperms_check_permission_<node>%`, `%stonyperms_expiry_time_<perm>%`, and `%stonyperms_context_<key>%`.

The proxy-side HTTP API lives in [`andreisugu/gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms) and is documented in [`docs/http-api.md`](https://github.com/andreisugu/gate-stonyperms/blob/main/docs/http-api.md).

---


## 🏠 Standalone Mode

Need a self-contained permission plugin/mod without running Gate or an HTTP backend? Set `mode: "standalone"` (Paper) or `"mode": "standalone"` (Fabric) to run `stonyperms-bridge` as an authoritative local permissions manager backed by the exact same JSON flat-file format and evaluation engine as [`gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms):

- **Shared Go ↔ Java parity:** Evaluation rules, RE2 regex matching (`r=...`), shorthand expansion (`{a,b}`, `{1-5}`), stacking affixes, `sp1_` portable web codes (`StonyPermsWeb` `/editor`, `/tree`, `/verbose`), audit logs, and storage files (`groups.json`, `tracks.json`, `logs.json`, `users/<uuid>.json`) match the canonical spec verified in CI (`core/src/test/resources/spec/`).
- **Storage Backends (`json`, `sqlite`, `mariadb`/`mysql`, `postgresql`):** Supports both flat-file JSON and HikariCP-pooled relational SQL storage using the canonical v1 schema shared with `gate-stonyperms`. Point both Gate and standalone backends at the same schema or copy JSON/SQLite files between them cleanly.
- **Conditional `/sp` commands:** In `standalone` mode, Paper and Fabric register `/sp` and `/stonyperms` alongside `/spb` (and accept `stonyperms.admin` / `stonyperms.*` in addition to `stonyperms.bridge.*`). In `bridge` mode, `/sp` is never registered so it never collides with Gate's `/sp` proxy command.

**Paper (`plugins/StonyPermsBridge/config.yml`):**
```yaml
mode: "standalone"
standalone:
  storage-type: "sqlite" # json | sqlite | mysql | mariadb | postgresql
  storage-dir: "data"
  database:
    host: "127.0.0.1"
    port: 0
    database: "stonyperms"
    username: "stonyperms"
    password-file: "" # or STONYPERMS_BRIDGE_DB_PASSWORD / STONYPERMS_DB_PASSWORD
    table-prefix: "stonyperms_"
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
      "tablePrefix": "stonyperms_"
    },
    "primaryGroupCalculation": "stored"
  }
}
```

---
## 🏗️ Architecture (v0.2)

```text
Gate + gate-stonyperms (single source of truth)
        │  Bearer HTTP/JSON + GET /v1/events SSE (read tier)
        │  Opt-in write tier (write.enabled: false by default)
        ├──────────────► Paper plugin ──► PermissionAttachment / Vault / PAPI / ServicesManager
        └──────────────► Fabric mod  ──► fabric-permissions-api / StonyPermsBridgeMod.api()
```

- **Near-real-time propagation:** When `sse.enabled: true` (default), backends subscribe to `GET /v1/events` (`text/event-stream`) with exponential backoff (`1000ms`–`30000ms`), refreshing changed online players immediately while keeping `ETag` / `If-None-Match` polling (`refresh-seconds: 30`) as a self-healing fallback.
- **Opt-in write capability:** Disabled (`write.enabled: false`) by default. Backends granted a separate write-tier token (`STONYPERMS_BRIDGE_WRITE_TOKEN`, `api.write-token`, or `api.write-token-file`) can run mutating `/spb user|group|track`, `/spb log`, and `/spb reload remote` commands and call `StonyPermsBridgeAPI` / Vault write methods.

### Modules

| Module | Output | Purpose |
| :--- | :--- | :--- |
| `core` | library / nested Fabric jar (`include(project(":core"))`) | HTTP client, SSE subscriber, snapshot cache, resolver, `StonyPermsBridgeAPI`, `/spb` command engine |
| `paper` | `stonyperms-bridge-paper-*.jar` | Paper plugin, attachment materializer, Vault, PlaceholderAPI, `/spb` |
| `fabric` | `stonyperms-bridge-fabric-*.jar` | Fabric mod (`fabric-permissions-api`), `/spb`, Fabric events |

---

## 🚀 Quick Start

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

Full setup: [`docs/install.md`](docs/install.md) | Store, stats & developer API workflows: [`docs/integrations.md`](docs/integrations.md).

---

## 🛠️ Build

Requires JDK 25+ and network access to Maven Central, PaperMC, and Fabric.

```bash
./gradlew build
./gradlew test
```

Outputs:
- `paper/build/libs/stonyperms-bridge-paper-0.2.0-SNAPSHOT.jar`
- `fabric/build/libs/stonyperms-bridge-fabric-0.2.0-SNAPSHOT.jar`

---

## ⌨️ Commands

Both platforms expose `/spb` with two distinct permissions (both default `op`):

- **`stonyperms.bridge.admin`** — `status`, `refresh [player]`, `check <player> <permission>`, `search <query> [page]`, `reload` (local bridge reload), `user <player> <info|permission info|parent info>`, `group list|<name> <info|members|permission info|parent info>`, `track list|<name> info`.
- **`stonyperms.bridge.write`** (additionally requires `write.enabled: true` and a write token) — `user <player> <group|parent|primarygroup|permission|meta|promote|demote|editor|clear> ...` (including `unsettemp`, `removetemp`, `settrack`, `cleartrack`, temp modifiers `accumulate|replace|deny`, `addprefix`/`addtempprefix`), `group <name> <create|delete|setweight|setdisplayname|permission|parent|meta|prefix|suffix|editor> ...`, `track <name> <create|delete|set|append|remove|clone> ...`, `bulkupdate <all|users|groups> <update|delete> ...`, `sync` / `networksync`, `editor [filter]`, `applyedits <code>`, `tree [root]`, `verbose paste`, `log [limit] [targetType=] [target=] [actor=]`, and `reload remote`.

> Running `/spb editor` on Paper collects every registered plugin permission node from `Bukkit.getPluginManager().getPermissions()` and live world names automatically, opening [StonyPermsWeb](https://andreisugu.github.io/gate-stonyperms/) with clickable Adventure URLs in chat.

`/spb check` asks the proxy directly (`POST /v1/check`); cached gameplay checks read the local snapshot cache.

---

## 🛡️ Failure Behavior & Limitations

- API unreachable with no cache: `fail-closed: true` denies StonyPerms-managed permissions; `false` falls back to platform defaults.
- API unreachable with a cached snapshot: served until `stale-grace-seconds`, then denied (or platform defaults if `fail-closed` is off).
- An explicit `undefined` evaluation means StonyPerms has no opinion, so the platform default applies.
- Operators are managed by StonyPerms by default (`apply-to-ops: true` on both Paper and Fabric); set `false` to skip ops entirely so they stay vanilla/standard-op.
- Missing or disabled write configuration fails closed before issuing HTTP writes (`403 writes_disabled` / `403 insufficient_scope` on the proxy as well).

See [`docs/limitations.md`](docs/limitations.md) for platform boundaries (Bukkit attachment materialization, Fabric `fabric-permissions-api` scope, single-line bridge token files).

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
| [`gate-stonyperms`](https://github.com/andreisugu/gate-stonyperms) | Context-aware permissions, tracks, Web UI & v0.2 R/W HTTP + SSE API | `Go 1.26` |
| **[`stonyperms-bridge`](https://github.com/andreisugu/stonyperms-bridge)** *(this repo)* | Paper & Fabric permission adapters, live SSE, `/spb` commands, Vault & PAPI | `Java 25` |
| [`gate-bettertab`](https://github.com/andreisugu/gate-bettertab) | Animated RGB / MiniMessage TAB lists, server groups, weights & placeholders | `Go 1.26` |
| [`gate-discord4gate`](https://github.com/andreisugu/gate-discord4gate) | Bidirectional Discord <> Minecraft chat, slash commands & webhook avatars | `Go 1.26` |
| [`gate-hybridforwarding`](https://github.com/andreisugu/gate-hybridforwarding) | Per-backend Velocity Modern, Legacy BungeeCord, BungeeGuard & None forwarding | `Go 1.26` |
| [`gate-simplewhitelist`](https://github.com/andreisugu/gate-simplewhitelist) | Lock-free `atomic.Pointer` whitelist by username, UUID, IP & CIDR | `Go 1.26` |
| [`gate-skinschanger`](https://github.com/andreisugu/gate-skinschanger) | Resilient multi-provider & Mineskin URL skin switcher with live backend refresh | `Go 1.26` |
| [`gate-smartlimbo`](https://github.com/andreisugu/gate-smartlimbo) | Limbo failover, protected sandbox & batched backend auto-reconnect queues | `Go 1.26` |
| [`gate-plugin-builder`](https://github.com/andreisugu/gate-plugin-builder) | Zero-config static Gate bundler (`build.sh`) + Pelican / Pterodactyl egg | `Shell` |

---

## 📄 License

Licensed under the [Apache License 2.0](LICENSE) © Andrei.
