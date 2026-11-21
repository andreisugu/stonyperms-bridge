# StonyPerms Canonical Engine & Storage Conformance Specification (v1)

This specification defines the authoritative behavior shared between:
- **Go reference implementation:** `gate-stonyperms` (`model`, `engine`, `storage`, `manager`)
- **Java standalone implementation:** `stonyperms-bridge:core` (`Node`, `LocalPermissionEngine`, `JsonLocalStorage`, `LocalPermissionManager`, `PortableWebCodec`)

Any behavioral change to permission evaluation, inheritance, contexts, expiration, affixes,
metadata, mutations, JSON storage layout, or `sp1_` portable web payloads MUST update this
directory (`spec/`), regenerate `spec/SHA256SUMS`, and pass conformance tests in BOTH repositories.

---

## 1. Normalization & Identifiers

- **Permission keys**: trimmed and compared lower-case (`strings.ToLower(strings.TrimSpace(k))`).
  Wait exception: regex pattern text after `r=` preserves original case of `<pattern>` for
  compilation with case-insensitive flag `(?i)`.
- **Group & track names**: lowercase trimmed matching `^[a-z0-9_+-]{1,64}$` for creation/rename.
- **Context keys & values**: keys trimmed & lowercased; values trimmed. Equality in `SameContexts`
  and query matching (`MatchesContext`) compares keys after lowercasing (stored normalized) and
  compares values **case-insensitively**.
- **Metadata keys**: trimmed (case preserved when stored; lookup tries exact key first, then
  sorted case-insensitive fallback).

---

## 2. Expiration Semantics

- `expiry <= 0` means **permanent** (never expires).
- `expiry > 0` is a Unix timestamp in **seconds**.
- At evaluation time `now` (Unix seconds `nowUnix`), an entry is expired iff `nowUnix >= expiry`.
- Expired entries are ignored during evaluation and removed by periodic expiry cleanup.

---

## 3. Context Matching

Given entry contexts `E` and query contexts `Q`:
1. If `E` is empty/null, `E` is global and matches any `Q` (including empty).
2. If `E` is non-empty and `Q` is empty/null, returns `false`.
3. For every `(k, v)` in `E`, `Q` must contain `k` (or exact key in `Q`) whose value equals `v`
   case-insensitively.

---

## 4. Node Pattern Matching (`EvaluateNodeMatch`)

Given `nodeKey` and `targetPerm` (trimmed & lowercased; empty -> `(false, false, 0)`):

1. **Regex (`r=<pattern>`)** when lowercased `nodeKey` starts with `r=`:
   - `pat = trim(nodeKey[2:])`; empty -> no match.
   - Compile with **linear-time RE2** (`regexp` in Go, `com.google.re2j` in Java) using
     pattern `(?i)^(?:<pat>)$` (bounded compile cache size 1024).
   - Invalid regex fails closed: `(false, false, 0)`.
   - Match returns `(true, isExact=false, specificity=len(pat))`.
2. **Literal / Wildcard (no `{` or `(` in lowercased `nodeKey`)**:
   - Exact equality `lk == t` -> `(true, true, len(t) + 1)`.
   - Universal wildcard `lk == "*"` -> `(true, false, 1)`.
   - Prefix wildcard `lk.endsWith(".*")`: let `prefix = lk.substring(0, lk.length() - 1)`
     (includes the dot). If `t.startsWith(prefix)` -> `(true, false, prefix.length())`.
   - Otherwise `(false, false, 0)`.
3. **Shorthand Expansion** when lowercased `nodeKey` contains `{` or `(` (not `r=`):
   - Iteratively expand first matching `{...}` or `(...)` up to 8 passes and at most 128
     candidates total (`maxShorthandExpansions = 128`).
   - Segment separators inside braces/parens: `|` takes precedence over `,`; if neither is
     present and `a-b` parses as two non-negative integers `a, b`, expand inclusive range
     (`step = +1` if `b >= a` else `-1`, capped at 128). Parts around `,`/`|` are trimmed.
   - Evaluate expanded candidates against `t`:
     - Any candidate `== t` immediately returns `(true, true, len(t))`.
     - Else `"*"` sets `matched=true, bestScore=max(bestScore, 1)`.
     - Else `"prefix.*"` matching `t.startsWith(prefix)` sets `matched=true, bestScore=max(bestScore, prefix.length())`.
     - Final return is `(matched, false, bestScore)`.

---

## 5. Single-Entity Node Selection (`selectBestNode`)

Iterate entity nodes in stored slice order, skipping expired or non-context-matching or non-matching nodes.
Candidate `n` beats current `best` iff:
1. `best == nil`, OR
2. `exact != bestExact`: `exact` wins (`exact == true` beats wildcard/regex), OR
3. Both exact (`exact == true`):
   - higher `len(n.contexts)` wins, else
   - higher `specificityScore` wins, else
   - `false` (`!n.value`) beats `true` (`best.value`).
4. Both non-exact (`exact == false`):
   - higher `specificityScore` wins, else
   - higher `len(n.contexts)` wins, else
   - `false` (`!n.value`) beats `true` (`best.value`).
5. On complete tie, keep earlier node in slice order.

---

## 6. Inheritance & Permission Evaluation

### 6.1 Group Inheritance Resolution (`ResolveInheritedGroups`)
Given `user`, `groups` map, `contexts`, `now`:
1. DFS with lowercase group-name `visited` set (prevents infinite cycles).
2. Visit active context-matching `user.parents` in slice order. For each existing group `g`:
   append `g` to `result` and recursively visit active context-matching `g.parents` in slice order.
3. Fallback visit `user.primaryGroup` (or `"default"` if empty) if not already visited.
4. Sort `result` stably by **`weight` descending**, tie-breaking alphabetically by lowercase **`name` ascending**.

*(Note: `ResolveGroupHierarchy` performs the same DFS + `(weight desc, name asc)` sort starting from a root group.)*

### 6.2 User & Group Evaluation (`EvaluateAt` / `GroupPermissionValueAt`)
- Nil user -> `Undefined` (`"none (nil user)"`).
- Check `user.nodes` via `selectBestNode`. If matched, return immediately:
  `value ? True : False`, source `"user:direct"` (exact) or `"user:wildcard (" + key + ")"`.
- Otherwise evaluate `ResolveInheritedGroups` in sorted order. First group yielding a non-null
  `selectBestNode` returns immediately:
  source `"group:" + g.name` (exact) or `"group:" + g.name + ":wildcard (" + key + ")"`.
- Otherwise return `Undefined` (`"default (undefined)"`).

### 6.3 Primary Group Calculation (`ResolvePrimaryGroupAt`)
Modes: `"stored"` (default), `"parents-by-weight"`, `"all-parents-by-weight"`.
- `"parents-by-weight"`: select active context-matching direct parent in `groups` with highest
  `weight`, tie-breaking alphabetically by `name` ascending. Fallback to stored/default.
- `"all-parents-by-weight"`: `resolvedGroups[0].name` if non-empty, else fallback.
- `"stored"`: normalized `user.primaryGroup` or `defaultGroup` (`"default"`).

---

## 7. Prefixes, Suffixes & Metadata

### 7.1 Single Affix (`resolveAffixAt`)
1. Highest active context-matching entry in `user.prefixes` / `user.suffixes`
   (higher `priority` wins; tie-break higher `len(contexts)`). If present, return its `value`.
2. Let `primary = ResolvePrimaryGroupAt(...)`. If non-empty and not `"default"` and exists in
   `groups`, return highest active context-matching affix on `primary` if present.
3. Otherwise scan `ResolveInheritedGroups` in order and keep candidate with highest
   `priority`, tie-breaking by higher `len(contexts)`, keeping earlier group on full tie.

### 7.2 Stacked Affixes (`formatStackedAffixAt`)
Collect active context-matching non-empty values from `direct` first, then each inherited group in
`ResolveInheritedGroups` order. Stably sort by `priority` descending, then `len(contexts)` descending,
then collection order ascending. Optionally deduplicate identical values keeping first occurrence.
If empty, return `""`; else `startSpacer + join(values, midSpacer) + endSpacer`.

### 7.3 Metadata (`ResolveAllMetaAt`, `GetMetaWithContextAt`)
Iterate user `(metaEntries, meta)` first, then each group in `ResolveInheritedGroups` order:
- Filter `metaEntries` to active & context-matching; sort stably by `len(contexts)` descending,
  then `priority` descending; insert keys absent from `result`.
- Iterate static `meta` keys in sorted order; insert keys absent from `result`.
`GetMetaWithContextAt` checks exact key in `result`, then alphabetically sorted case-insensitive fallback.

---

## 8. Mutations, Storage & Portable Web Payloads

- **Temp modifiers**: `replace` (keeps `max(existingExpiry, newExpiry)`), `accumulate`
  (adds `newExpiry - nowUnix` to active `existingExpiry`), `deny` (errors if non-expired matching
  entry already exists). Permanent entries override without duration; temp additions never shorten
  an active permanent entry (and error under `deny`).
- **Renames**:
  - `RenameGroup`: forbids renaming `"default"` / configured `defaultGroup`, rejects target collision,
    atomically updates all group `parents`, track `groups`, user `primaryGroup` & `parents`.
  - `RenameTrack`: forbids collision with existing track and saves/deletes atomically.
- **BulkUpdate**: scope `all|users|groups`, action `update|delete`, update fields `permission|server|world`,
  constraints `permission|value|server|world` with `==|!=|~~|!~`, dry-run preview without disk mutation.
- **JSON Storage**: `groups.json`, `tracks.json`, `logs.json` (max 1000 entries), `users/<uuid>.json`.
- **Portable Web Codes**: `"sp1_"` + unpadded URL-safe Base64 of `gzip` (best compression) JSON,
  capped at 64 KiB decompressed.

---

## 10. Canonical Relational SQL Schema (v1)

Both `gate-stonyperms` (`storage/sql.go`) and `stonyperms-bridge` (`local/SqlLocalStorage.java`) implement
this shared normalized schema across **SQLite**, **MySQL / MariaDB**, and **PostgreSQL**, enabling
cross-engine and cross-language portability alongside the `"json"` flat-file backend.

### 10.1 Storage Types & Configuration
- `storage_type` / `storageType`: `"json"` (default), `"sqlite"`, `"mysql"`, `"mariadb"`, `"postgresql"` (`"postgres"` normalized to `"postgresql"`).
- `table_prefix` / `tablePrefix`: validated strictly against `^[a-zA-Z0-9_]{0,32}$` (default `"stonyperms_"`).
- Password resolution order (never logged): environment variable (`STONYPERMS_DB_PASSWORD`, or `STONYPERMS_BRIDGE_DB_PASSWORD` -> `STONYPERMS_DB_PASSWORD` in Java bridge) -> inline `password` -> `password_file`/`passwordFile`.

### 10.2 Table Layout (prefixed by `{p}`)
1. `{p}schema_meta`: `version INTEGER PRIMARY KEY, applied_at BIGINT NOT NULL` (current schema version = `1`).
2. `{p}groups`: `name VARCHAR(64) PRIMARY KEY, display_name VARCHAR(255) NOT NULL DEFAULT '', weight INTEGER NOT NULL DEFAULT 0`.
3. `{p}group_nodes`: `group_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, perm_key VARCHAR(255) NOT NULL, value <BOOL> NOT NULL, expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, PRIMARY KEY (group_name, position), FOREIGN KEY (group_name) REFERENCES {p}groups(name) ON DELETE CASCADE`, index on `(perm_key)`.
4. `{p}group_parents`: `group_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, parent_group VARCHAR(64) NOT NULL, expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, PRIMARY KEY (group_name, position)`, FK CASCADE to `{p}groups(name)`.
5. `{p}group_affixes`: `group_name VARCHAR(64) NOT NULL, kind VARCHAR(8) NOT NULL, position INTEGER NOT NULL, priority INTEGER NOT NULL DEFAULT 0, value TEXT NOT NULL, expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, PRIMARY KEY (group_name, kind, position)`, FK CASCADE to `{p}groups(name)` (`kind` is `'prefix'` or `'suffix'`).
6. `{p}group_meta`: `group_name VARCHAR(64) NOT NULL, meta_key VARCHAR(128) NOT NULL, meta_value TEXT NOT NULL, PRIMARY KEY (group_name, meta_key)`, FK CASCADE to `{p}groups(name)` (MySQL/MariaDB uses `COLLATE utf8mb4_bin` on `meta_key` so key casing is preserved).
7. `{p}group_meta_entries`: `group_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, meta_key VARCHAR(128) NOT NULL, meta_value TEXT NOT NULL, priority INTEGER NOT NULL DEFAULT 0, expiry BIGINT NOT NULL DEFAULT 0, contexts TEXT NOT NULL, PRIMARY KEY (group_name, position)`, FK CASCADE to `{p}groups(name)`.
8. `{p}users`: `uuid CHAR(36) PRIMARY KEY, username VARCHAR(64) NOT NULL DEFAULT '', primary_group VARCHAR(64) NOT NULL DEFAULT 'default'`, index on `(username)`.
9. `{p}user_nodes`: mirror of `{p}group_nodes` keyed by `user_uuid CHAR(36)` with FK CASCADE to `{p}users(uuid)`.
10. `{p}user_parents`: mirror of `{p}group_parents` keyed by `user_uuid CHAR(36)` with FK CASCADE to `{p}users(uuid)`.
11. `{p}user_affixes`: mirror of `{p}group_affixes` keyed by `user_uuid CHAR(36)` with FK CASCADE to `{p}users(uuid)`.
12. `{p}user_meta`: mirror of `{p}group_meta` keyed by `user_uuid CHAR(36)` with FK CASCADE to `{p}users(uuid)`.
13. `{p}user_meta_entries`: mirror of `{p}group_meta_entries` keyed by `user_uuid CHAR(36)` with FK CASCADE to `{p}users(uuid)`.
14. `{p}tracks`: `name VARCHAR(64) PRIMARY KEY`.
15. `{p}track_groups`: `track_name VARCHAR(64) NOT NULL, position INTEGER NOT NULL, group_name VARCHAR(64) NOT NULL, PRIMARY KEY (track_name, position)`, FK CASCADE to `{p}tracks(name)`.
16. `{p}logs`: `id <AUTO_PK>, timestamp BIGINT NOT NULL, actor VARCHAR(128) NOT NULL, target_type VARCHAR(32) NOT NULL, target VARCHAR(128) NOT NULL, action TEXT NOT NULL`, index on `(timestamp, id)`.

### 10.3 Persistence Semantics
- **Slice ordering:** Nodes, parents, affixes, `metaEntries`, and track groups preserve exact insertion order via `position` (`0..N-1`) and `ORDER BY position ASC` on load.
- **Contexts:** Stored as canonical compact JSON object with normalized lowercase keys (`"{}"` when empty).
- **Atomic writes:** `SaveUser`, `SaveGroup`, `SaveTrack`, `DeleteUser`, `DeleteGroup`, and `DeleteTrack` run inside a database transaction (`BEGIN` ... `COMMIT`), upserting the parent row and replacing child rows atomically.
- **Default seeding:** Inside a transaction on startup, seed `default`, `admin`, `ranks`, and the initial log entry **only** when `{p}groups` contains `0` rows.
- **Logs:** SQL retains full append-only audit history; `LoadAll` returns up to the latest `1000` logs in chronological ascending order, matching JSON storage.
