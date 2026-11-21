# Security model (v0.2)

## Trust boundary

The StonyPerms HTTP API is an internal service-to-service control interface, not a public endpoint. Read access exposes resolved permission snapshots, group weights, metadata, prefixes, and user records; write access grants full mutating control over users, groups, tracks, reloads, and audit logs. Never expose port `8737` to the public internet.

## Dual-Tier Authentication & Least Privilege

- **Separate Read and Write tiers:**
  - Read tokens (`STONYPERMS_API_TOKEN` / `api_token_file` on Gate; `STONYPERMS_BRIDGE_TOKEN` / `api.token-file` on bridges) allow read queries, `/v1/check`, and `GET /v1/events` SSE only.
  - Write tokens (`STONYPERMS_API_WRITE_TOKEN` / `api_write_token_file` on Gate; `STONYPERMS_BRIDGE_WRITE_TOKEN` / `api.write-token-file` on bridges) are opt-in and only accepted when `api_write_enabled = true` on Gate.
- **No silent fallback & overlap rejection:**
  - Gate fails closed if `api_write_enabled = true` and the write token fails to load — it disables writes (`writeEnabled = false`) and never accepts a read token for mutations.
  - `api.New` rejects any configuration where a read token also appears in the write token list.
  - A read-only token hitting a write route or `GET /v1/logs` fails with `403 insufficient_scope` (or `403 writes_disabled` when `api_write_enabled = false`).
- **Backends remain least-privilege by default:** `write.enabled` defaults to `false` in both Paper (`config.yml`) and Fabric (`stonyperms-bridge.json`). Only backends that genuinely execute trusted console fulfillment or cross-server admin commands (e.g., PayNow fulfillment on Purpur lobby) should hold a write token; gameplay backends (Survival, Creative) should stay read-only (`write.enabled: false` with no write token on disk) and rely on SSE for instant updates.

## Token Handling & Single-Line Bridge Files

- Constant-time comparison (`crypto/subtle`) on Gate, minimum 16 characters, 32+ random hex characters (`openssl rand -hex 32`) recommended.
- Store token files with mode `0600` owned strictly by the service user. Never place tokens in git, URLs, logs, or error responses.
- **Bridge resolution precedence:**
  - Read: `STONYPERMS_BRIDGE_TOKEN` env var -> inline `api.token` -> `api.token-file` (`tokenFile` on Fabric).
  - Write (checked only when `write.enabled: true`): `STONYPERMS_BRIDGE_WRITE_TOKEN` env var -> inline `api.write-token` (`writeToken`) -> `api.write-token-file` (`writeTokenFile`).
- **Single-line bridge token nuance:** While `gate-stonyperms` accepts up to 4 newline-separated tokens in its proxy token files for zero-downtime rotation, `stonyperms-bridge` (`PaperBridgeConfig` and `FabricBridgeConfig`) reads each backend token file as a single trimmed string (`Files.readString(path).trim()`). Always provision each bridge backend with its own **single-token `0600` file** rather than copying a multi-line proxy rotation file verbatim.

## Network & Transport

- Default proxy bind: `127.0.0.1:8737`. Non-loopback binds require `api_allow_remote = true`.
- Remote backends should connect only over Tailscale/WireGuard with host firewall rules allowing backend IPs only, plus TLS (`api_tls_cert` / `api_tls_key` or TLS-terminating reverse proxy) when crossing untrusted hops.
- Adapters log a warning when configured for plain HTTP against a non-loopback host, disable redirects (`Redirect.NEVER`), and apply bounded timeouts (`connectTimeoutMs = 1500`, `requestTimeoutMs = 2500`).

## Backend Command & Platform Isolation

- **Two command permission nodes** (both default `op`):
  - `stonyperms.bridge.admin`: diagnostics (`status`, `refresh`, `check`), local `reload`, and read-only lookups (`user info`, `group list|info|members`, `track list|info`).
  - `stonyperms.bridge.write`: cross-server mutations (`user`, `group`, `track`), `log`, and `reload remote`. Mutating `/spb` commands also fail closed whenever `write.enabled: false`.
- **Fabric `applyToOps` (`true` default) & break-glass:**
  - When `applyToOps: false`, `FabricBridgeRuntime.isOpBypassed` defers `fabric-permissions-api` checks for server ops (`TriState.DEFAULT`) rather than enforcing StonyPerms grants/denials on ops.
  - Independently, Fabric `/spb` retains console and `ops.json` (`playerList.isOp(...)`) break-glass access so admins can always inspect or reload a broken bridge.
  - Fabric `/spb reload` requires `hasAdminPermission`; `/spb reload remote` additionally requires `hasWritePermission` (both admin and write permission plus `write.enabled: true`).
- **Paper zero-shading plugin isolation:** `paper/build.gradle.kts` compiles against `VaultAPI` and `PlaceholderAPI` via `compileOnly` with `softdepend: [Vault, PlaceholderAPI]` in `plugin.yml`. Neither optional dependency JAR is shaded into the bridge artifact, avoiding classpath pollution and letting Paper isolate optional integrations cleanly.

## Abuse Controls, Idempotency & Auditability

- **Per-IP rate limiting on Gate:** Default `60/s` burst `120` for reads and `15/s` burst `30` for write/admin routes (`RemoteAddr`-based, ignoring `X-Forwarded-For`), returning `429 rate_limited` + `Retry-After: 1`.
- **Idempotency & conflict detection:** HTTP writes supporting `Idempotency-Key` verify a SHA-256 digest of canonicalized query parameters and raw JSON body, rejecting payload reuse mismatches with `409 idempotency_conflict`.
- **Fail-closed user resolution:** User mutation paths reject unknown usernames with `404 user_not_found`; offline UUID synthesis only occurs when explicitly requested via `POST /v1/users` with `"createOffline": true`.
- **Audit trail:** Gate records `X-StonyPerms-Actor` (defaults on `/spb` to sender username / `"console"`, and on programmatic bridge API calls to `write.default-actor` or `paper:<server>` / `fabric:<server>`) in StonyPerms' audit log and SSE change events.
