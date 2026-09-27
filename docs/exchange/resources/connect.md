# Connect

Three routes under `exchange.connect` that a client calls before it syncs anything: what this token
may do, which installation it is, and whether the game account in front of it is the member's. None
of them is a write: they need no `Idempotency-Key` and do not count against the daily write quota.

## Service document — `GET /exchange/v1`

No parameters. The answer is a
[`service-document`](../schemas/) and describes this token, not the API in general:

| Field | Meaning |
| --- | --- |
| `apiVersion` | `1.<minor>`; `1.0` today. |
| `capabilities` | The scopes that both the token and the client registry grant, sorted. A route whose scope is missing answers `403 SCOPE_MISSING`. |
| `installationId` | Opaque id of this installation — the value `removedBy.installationId` carries on a tombstone this installation caused. Absent when the server could not look it up. |
| `limits.batchMaxOps` | The largest change set: 500 ops. |
| `limits.requestsPerMinute` | Present only when the registry sets this client's own per-member limit; the default is in the [sync guide](../sync-guide.md#rate-limits-quota-and-back-off). |
| `limits.writesPerDay` | Present only when the registry sets this client's own daily write quota. |
| `deprecations` | `{feature, sunset?, link?}` for every deprecated feature; empty in `v1` today. |
| `docsUrl` | This documentation. |
| `minClientVersion` | The registry's minimum version for this client, or `null`. |

```json
{
  "apiVersion": "1.0",
  "capabilities": ["exchange.blueprints.read", "exchange.blueprints.write", "exchange.connect"],
  "installationId": "inst-7f3c2a9e",
  "limits": {"batchMaxOps": 500, "requestsPerMinute": 60, "writesPerDay": 2000},
  "deprecations": [],
  "docsUrl": "https://krt-profit.github.io/basetool/exchange/",
  "minClientVersion": "3.60.0"
}
```

Read it at the start of every session. Offer only the features whose scopes are in `capabilities`,
store `installationId`, and tell the member to update when your version is below
`minClientVersion` — the gateway refuses such a release with `403 CLIENT_VERSION_UNSUPPORTED` anyway.

## Label the installation — `POST /exchange/v1/me/installation`

An installation is one client on one device for one member, identified by the thumbprint of its DPoP
key. The server creates it the first time that key calls any exchange route; a new key is a new
installation. The label lets the member tell installations apart in „Verbundene Anwendungen", where
it is always shown after the registered client name.

The body is an [`installation`](../schemas/) with only `label`: at most 40 characters of letters,
digits, space, `-`, `_` and `.`, not starting with a space. The server normalises it to NFC and
checks the rule again. Never send a host or computer name. `installationId`, `firstSeenAt` and
`lastSeenAt` are ignored on input.

```json
{"label": "VerseKit Windows"}
```

The answer is the installation:

```json
{
  "label": "VerseKit Windows",
  "installationId": "inst-7f3c2a9e",
  "firstSeenAt": "2026-09-26T12:00:00Z",
  "lastSeenAt": "2026-09-26T18:00:00Z"
}
```

`lastSeenAt` moves forward at most every five minutes. Call the route again whenever the member
changes the label; the last label wins.

## Account check — `POST /exchange/v1/me/account-check`

Tells a client whether the RSI handle it read from the game log belongs to the signed-in member, so
it never syncs another account's data into the member's Basetool. This is the only route an RSI
handle may be sent to.

The body is `{"handle": "…"}` with a handle of 3 to 60 characters of `A–Z`, `a–z`, `0–9`, `_` and
`-`. The answer is `{"result": "match" | "mismatch" | "unknown"}`:

| `result` | Meaning | What a client does |
| --- | --- | --- |
| `match` | The handle equals the one on the member's profile, ignoring case. | Sync. |
| `mismatch` | The member stored a different handle. | Stop and ask the member; the log may be another account's. |
| `unknown` | The member stored no handle. | Ask the member to confirm the account, or to enter the handle in their profile. |

The stored handle is never returned and never logged. The check has its own limit of 10 per hour per
client and member (`429 RATE_LIMITED` with `Retry-After`); check once per handle and remember the
answer for the session.

## Errors

| Code | When |
| --- | --- |
| `400 SCHEMA_INVALID` | The label or the handle breaks its rule. `errors[]` names the pointer, never the value. From the server without `errors[]`: the label broke the rule only after normalisation. |
| `429 RATE_LIMITED` | The per-minute limit, or the account check's hourly one, is used up. |

Every route can also answer the gateway's authentication and registry codes. The full list, with
what a client does about each, is the [error registry](../errors.md).
