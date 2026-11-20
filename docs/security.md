# Security model

## Trust boundary

The bridge API is an internal service-to-service interface. It exposes resolved
permission data, including group names, weights, prefixes, and metadata. Treat
it like a database read credential, not a public API.

## Authentication

- One shared bearer token per deployment.
- Proxy: constant-time comparison (`crypto/subtle`), minimum 16 characters in
  code, 32+ random characters recommended.
- Token sources, in order: `STONYPERMS_API_TOKEN` (proxy) /
  `STONYPERMS_BRIDGE_TOKEN` (backend), token file, inline config.
- The proxy refuses to start the API without a token. The backend disables
  itself if it cannot resolve one.
- Tokens are never placed in URLs, logs, error messages, or API responses.
- Token files should be mode `0600` and owned by the service user.

Generate tokens with a CSPRNG:

```bash
openssl rand -hex 32
```

Do not reuse the Gate forwarding secret, a database password, or an operator
account token.

## Network

- Default bind: `127.0.0.1:8737`.
- Non-loopback binds are rejected unless `api_allow_remote = true`.
- Preferred remote deployment: Tailscale/WireGuard address, firewall only the
  backend hosts, no public listener.
- If traffic crosses an untrusted network, enable TLS with
  `api_tls_cert`/`api_tls_key`, or terminate TLS in a reverse proxy that
  forwards the `Authorization` header.
- The backend warns when it uses plain HTTP to a non-loopback host. It does not
  silently downgrade.

## Authorization

- Every `/v1/*` route requires the bearer token; there are no per-user scopes.
- `/healthz` is the only unauthenticated route and returns no data.
- The API is read-only. It has no create/update/delete routes and does not
  expose audit logs or storage paths.
- Backend admin commands (`/spb`) use the Bukkit/Fabric admin permission
  (`stonyperms.bridge.admin`, default op). On Fabric, console and vanilla ops
  can always run `/spb` so a broken API can be repaired.

## Data exposure

A snapshot contains:

- UUID, username, primary group;
- resolved group names/weights/display names;
- nodes (keys, boolean values, contexts, expiry, source);
- prefix, suffix, and metadata.

It does not contain:

- other users' data;
- audit logs;
- storage files or paths;
- the token;
- Gate configuration.

Metadata can contain arbitrary strings set by admins. Do not put secrets in
StonyPerms metadata or prefixes.

## Fail-closed semantics

- `fail-closed: true` means: if there is no usable snapshot, do not grant
  StonyPerms-managed permissions. It does not deop players or override the
  platform's own defaults for nodes StonyPerms never managed.
- `use-stale-on-error: true` serves the last snapshot for
  `stale-grace-seconds`. Set the grace to the longest outage you are willing to
  tolerate with stale grants.
- A defined `undefined` result is not a failure; it means StonyPerms has no
  opinion and the platform default applies.
- Paper ops retain op defaults unless `apply-to-ops: false`. If you need
  StonyPerms to fully own operator permissions, set `apply-to-ops: false` only
  after confirming your break-glass path.

## Availability and abuse

- Proxy: 5 s header timeout, 10 s read, 15 s write, 16 KiB headers, 64 KiB
  request body, 16 contexts max.
- Backend: 1.5 s connect / 2.5 s request by default, no redirects, two HTTP
  worker threads, no network I/O on the server thread.
- Repeated failures are logged at most every five minutes unless `debug` is on.
- The API has no rate limiter beyond the HTTP server limits. Keep it on a
  private network; a hostile local process with the token can read permission
  data.

## Secret rotation

1. Generate a new token.
2. Update the proxy token file/environment and restart or reload Gate.
3. Update all backend token files/environments and restart them.
4. Old tokens stop working immediately when the proxy restarts.

For zero-downtime rotation, run the new proxy token alongside the old one in a
future API version; v1 supports one token.

## What this design deliberately does not do

- No write endpoints, so a compromised backend cannot change proxy permissions.
- No shared database credentials on backends.
- No plugin-messaging secrets or player-bound channels.
- No implicit trust based on source IP; the token is always required.
