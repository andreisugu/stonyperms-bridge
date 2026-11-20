# API contract used by the bridge

The authoritative contract and security notes live in
[`gate-stonyperms/docs/http-api.md`](https://github.com/andreisugu/gate-stonyperms/blob/main/docs/http-api.md).
This page records what this repository relies on.

## Base URL and auth

- Base URL: `http://127.0.0.1:8737` by default.
- Every `/v1/*` request: `Authorization: Bearer <token>`.
- `GET /healthz` is unauthenticated and returns only `{"status":"ok"}`.
- No cookies, no query-string token, no CORS.

## Schema v1

### Snapshot

`GET /v1/players/{uuid}?<contexts>`

```json
{
  "schemaVersion": 1,
  "revision": 42,
  "generatedAt": "2026-11-20T12:00:00Z",
  "ttlSeconds": 30,
  "user": {
    "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
    "username": "Notch",
    "primaryGroup": "default",
    "synthetic": false,
    "meta": {"rank": "gold"},
    "prefix": "&aVIP ",
    "suffix": ""
  },
  "groups": [
    {"name": "admin", "displayName": "&cAdmin", "weight": 100}
  ],
  "nodes": [
    {
      "key": "*",
      "value": true,
      "contexts": {},
      "expiry": 0,
      "source": "group:admin",
      "weight": 100,
      "user": false
    }
  ]
}
```

Contract details this client depends on:

- `nodes` are already filtered for the requested contexts and expiry.
- `groups` are in descending weight order; this order is the source precedence.
- Within a source, exact beats wildcard; longest wildcard wins.
- `user: true` nodes come first and beat every group.
- `source` is `"user"` or `"group:<name>"`.
- `user.synthetic: true` means no stored user; the snapshot is resolved from the
  default group. Treat it as valid.
- `ttlSeconds` is advisory; caching must still honor the configured stale
  grace and fail-closed policy.
- Unknown JSON fields are ignored by the client (Gson default).

### Revision and ETag

`GET /v1/revision` → `{"revision":42}`

`GET /v1/players/...` returns an ETag such as
`"sp-<generation>-42-<fingerprint>"`. Send it as `If-None-Match`; a `304` means
the cached snapshot is still current. The client extends the fresh window on
304 without re-applying anything. The generation changes on proxy restart and
the fingerprint covers effective state such as temporary-node expiry, so a 304
is never reused for a different grant.

### Authoritative check

`POST /v1/check`

```json
{"uuid":"...","permission":"example.node","contexts":{"server":"lobby"}}
```

```json
{"value":"true","source":"group:admin:wildcard(*)","revision":42}
```

`value` is `"true"`, `"false"`, or `"undefined"`. The bridge uses this only for
`/spb check`; normal gameplay reads the cache.

## Client behavior

- Timeouts: 1.5 s connect, 2.5 s request by default.
- No redirects (a redirected API is a configuration error).
- 401/403/5xx and malformed JSON are failures; the last good snapshot is kept
  until the stale grace expires.
- Error messages include the status and a short reason, never the response
  body or token.
- Context keys/values are URL-encoded and sorted for a stable cache key.

## Compatibility

- `schemaVersion` must be `1`. A future breaking change gets `/v2`.
- Additive fields are allowed.
- The bridge refuses to start without a token of at least 16 characters; 32+
  random characters are recommended.
