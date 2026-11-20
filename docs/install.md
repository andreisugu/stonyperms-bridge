# Install and configure

Nothing here was applied to a live server. These are the staged steps for the
owner.

## 1. Gate / StonyPerms

On the Gate host:

1. Update to a `gate-stonyperms` build containing the read-only API (PR
   `andreisugu/gate-stonyperms#1`).
2. Enable the API in `config/stonyperms.toml`:

   ```toml
   api_enabled = true
   api_listen = "127.0.0.1:8737"
   api_token_file = "config/stonyperms-api.token"
   api_allow_remote = false
   api_tls_cert = ""
   api_tls_key = ""
   api_snapshot_ttl_seconds = 30
   ```

3. Create the token:

   ```bash
   umask 077
   openssl rand -hex 32 > config/stonyperms-api.token
   chmod 600 config/stonyperms-api.token
   ```

4. Restart Gate (or reload, if your build reloads plugin config) and verify:

   ```bash
   TOKEN="$(cat config/stonyperms-api.token)"
   curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8737/v1/health
   curl -s -H "Authorization: Bearer $TOKEN" \
     "http://127.0.0.1:8737/v1/players/069a79f4-44e9-4726-a5be-fca90e38aaf5?server=lobby"
   ```

If the API is remote, bind the tailnet address and set
`api_allow_remote = true`, or terminate TLS in front of it. Never expose it to
the public internet.

## 2. Paper

1. Drop `stonyperms-bridge-paper-0.1.0-SNAPSHOT.jar` into `plugins/`.
2. Start once to create `plugins/StonyPermsBridge/config.yml`, then stop.
3. Configure:

   ```yaml
   api:
     url: "http://127.0.0.1:8737"
     token: ""
     token-file: "token"        # relative to plugins/StonyPermsBridge/
     connect-timeout-ms: 1500
     request-timeout-ms: 2500

   server-name: "lobby"         # must match StonyPerms' backend name
   refresh-seconds: 30
   stale-grace-seconds: 300
   fail-closed: true
   use-stale-on-error: true
   apply-to-ops: true
   debug: false
   ```

4. Put the token in `plugins/StonyPermsBridge/token` with mode `600`, or set
   `STONYPERMS_BRIDGE_TOKEN` in the service environment.

   ```bash
   install -m 600 /path/to/copied/token plugins/StonyPermsBridge/token
   ```

5. Restart Paper and check:

   ```text
   /spb status
   /spb check <player> <node>
   ```

`server-name` must match the name Gate uses in its `server` context. If you do
not use per-server contexts, it can be empty.

### Paper behavior

- Permissions are materialized for the Bukkit permission universe at apply
  time: everything registered by plugins plus literal StonyPerms nodes.
- Wildcards (`*`, `prefix.*`) are expanded against that universe.
- A node that no plugin declares and that is not a literal in the snapshot
  cannot be intercepted. If a plugin checks such a node, it sees its own
  default. Ask the plugin to declare its permissions in `plugin.yml`, or add a
  literal StonyPerms node for it.
- `apply-to-ops: true` (default) applies StonyPerms attachments to ops; `false` skips ops entirely and leaves them vanilla.

## 3. Fabric

Requirements on the Fabric server:

- Minecraft 26.3
- Fabric Loader 0.20.x
- Fabric API 0.166.x+26.3
- `fabric-permissions-api` 0.7.0+ (`fabric-permissions-api-v0`)

1. Drop `stonyperms-bridge-fabric-0.1.0-SNAPSHOT.jar`,
   `fabric-api-*.jar`, and `fabric-permissions-api-*.jar` into `mods/`.
2. Start once to create `config/stonyperms-bridge.json`, then stop.
3. Configure:

   ```json
   {
     "api": {
       "url": "http://127.0.0.1:8737",
       "token": "",
       "tokenFile": "token",
       "connectTimeoutMs": 1500,
       "requestTimeoutMs": 2500
     },
     "serverName": "survival",
     "refreshSeconds": 30,
     "staleGraceSeconds": 300,
     "failClosed": true,
     "useStaleOnError": true,
     "debug": false
   }
   ```

4. Put the token in `config/token` (relative to the Fabric config directory)
   with mode `600`, or set `STONYPERMS_BRIDGE_TOKEN`.
5. Restart and check `/spb status` in the server console or as an op.

### Fabric behavior

- `fabric-permissions-api`'s `PermissionCheckEvent` is answered on demand:
  wildcards and contexts work for any node.
- `Options` (`prefix`, `suffix`, arbitrary meta keys) are provided.
- Offline checks use the last snapshot fetched for that UUID.
- Mods that never call `fabric-permissions-api` are not covered. That includes
  vanilla `/gamemode`-style permission-level checks in 26.3. A future mixin
  can map `ServerPlayer.permissions()`/command levels to StonyPerms nodes; it
  is intentionally not part of this first slice.
- Do not run another `PermissionCheckEvent` provider (for example a second
  permissions mod) expecting deterministic ordering: the first non-`DEFAULT`
  listener wins. Run the bridge as the only provider.

## 4. Common checks

```bash
# proxy truth
/sp check Steve example.node

# backend snapshot/attachment
/spb status
/spb check Steve example.node

# force a refresh after a group change
/spb refresh Steve
```

If `/spb status` shows a last error, check in this order:

1. Is `api_enabled = true` and is Gate listening?
2. Does the token match (environment variable first)?
3. Is `server-name` the same backend name StonyPerms uses?
4. Is the backend clock sane? Expiry and TTL use wall-clock time.
5. For Paper, is the node declared by a plugin or present literally in the
   snapshot?

## 5. Ports and network

| From | To | Port | Notes |
| --- | --- | --- | --- |
| Paper/Fabric host | Gate host | 8737/tcp | loopback or tailnet/WireGuard |
| Backend | Gate | 25565/tcp | existing player traffic |

Do not open 8737 to the internet. If you must cross an untrusted network, set
`api_tls_cert`/`api_tls_key` and use `https://`.
