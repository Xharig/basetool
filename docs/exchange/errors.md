# Exchange API v1 — error codes

Every error of the exchange API is an RFC 9457 `application/problem+json` document
([`problem.schema.json`](https://ingest.profit-base.online/exchange/v1/schemas/problem.schema.json))
whose `code` is one of the codes below (`REQ-XCH-025`). A code is never reused or repurposed; new
codes may appear within v1, and a client treats an unknown code by its HTTP status.

The `detail` is a short English sentence fixed per code and situation. It never echoes the request,
and a refusal raised by the Basetool behind the gateway arrives with its code's own sentence, not
the Basetool's internal text, whatever the `Accept-Language`. Decide by `code`; show `detail` at
most as a hint.

The **gateway** codes are the `reason` label values of the gateway's exchange metrics, in snake
case. The **per-op** reasons never arrive as a problem: they appear in a change result's
`results[].reason` for an op that was not applied.

## Request errors

| Code | HTTP | Raised by | Meaning | Client action |
| --- | --- | --- | --- | --- |
| `CLIENT_NOT_ALLOWED` | 403 | gateway | The token's client is not in the registry. | Stop; the client is not approved. |
| `CLIENT_SUSPENDED` | 403 | gateway | The client is suspended in the registry. | Stop and tell the member; retry after the maintainer resolved it. |
| `CLIENT_REVOKED` | 401 | gateway | The member disconnected this client after this connection was made: an offline token issued before the disconnect, or a token without `offline_access` whose sign-in (`auth_time`) came before it. | Discard tokens; start a new device login only when the member asks. |
| `INSTALLATION_REVOKED` | 401 | gateway | The member disconnected this installation; its DPoP key is refused for good. | Discard tokens **and** the DPoP key; reconnecting needs a new key. |
| `CLIENT_VERSION_UNSUPPORTED` | 403 | gateway | The `User-Agent` version is below the client's minimum. | Ask the member to update. |
| `EXCHANGE_DISABLED` | 503 | gateway | The exchange is switched off globally. | Back off; retry later. |
| `REGISTRY_UNAVAILABLE` | 503 | gateway | The gateway cannot read the client registry and fails closed. | Back off; retry later. |
| `EXCHANGE_BUDGET_EXHAUSTED` | 503 | gateway | A Redis byte budget of the exchange is full. | Back off; honour `Retry-After`. |
| `SCOPE_MISSING` | 403 | gateway | The route's capability is not in the token or not granted to the client. A missing consent looks the same. | Start a device login requesting the scope, if the member wants it. |
| `UNAUTHENTICATED` | 401 | gateway | The token is missing, invalid, expired, or not issued for this gateway. | Start a device login again. |
| `DPOP_REQUIRED` | 401 | gateway | The request carries no DPoP proof or an unbound token. | Send `Authorization: DPoP` with a proof. |
| `DPOP_INVALID` | 401 | gateway | The proof is invalid, replayed, for another key, or lacks the server nonce. | Fix the proof; on a nonce challenge retry once with the `DPoP-Nonce`. |
| `TERMS_NOT_ACCEPTED` | 403 | backend | The member has not accepted the current terms. | Ask the member to open the Basetool and accept. |
| `PENDING_APPROVAL` | 403 | backend | The member's registration awaits approval. | Stop; nothing to sync yet. |
| `NO_ROLE` | 403 | backend | The member holds no role. | Stop and tell the member. |
| `ACTING_MEMBER_REFUSED` | 403 | backend | The relay refused the member (unknown, disabled or deleted). | Stop and tell the member. |
| `NOT_PERMITTED` | 403 | backend | The member may not do this. | Stop; do not retry. |
| `SCHEMA_INVALID` | 400 | gateway, backend | The body or a query parameter does not match the v1 contract; `errors[]` points at the fields, a parameter as `/<name>`. From the backend, without `errors[]`: the content is malformed although it matches the schema, such as a refinery draft of an unsupported panel type. | Fix the request. |
| `BATCH_TOO_LARGE` | 413 | gateway | A change set holds more than 500 ops, or is too large to hold for the member's confirmation. | Split the batch. |
| `PAYLOAD_TOO_LARGE` | 413 | gateway | The body exceeds the size cap, or the draft built from it is too large to hand off. | Split or shrink the request. |
| `IDEMPOTENCY_KEY_MISSING` | 400 | gateway | A write carries no `Idempotency-Key`. | Send a fresh key per logical write. |
| `IDEMPOTENCY_KEY_REUSED` | 422 | gateway | The key was used with a different body. | Use a fresh key. |
| `IDEMPOTENCY_IN_PROGRESS` | 409 | gateway | The same key is still being processed. | Retry the same request after a short wait. |
| `CURSOR_EXPIRED` | 410 | backend, gateway | The cursor is older than the tombstones, or not one the server issued. | Reconcile a full snapshot against the last baseline — not add-only. |
| `VERSION_CONFLICT` | 409 | backend | A ship's `version` or a lot's `expectedQuantity` no longer matches. | Pull, merge, retry. |
| `MASS_CHANGE_CONFIRMATION_REQUIRED` | 409 | backend | The batch exceeds the mass-change guard; it is staged. | Show the member `confirmationUrl`; do not retry the batch. |
| `RATE_LIMITED` | 429 | gateway | A per-minute limit is exhausted. | Honour `Retry-After`. |
| `QUOTA_EXCEEDED` | 429 | gateway | The daily write quota is exhausted. | Retry after `Retry-After`, the next day at the latest. |
| `BACKEND_RELAY_FAILED` | 502 | gateway | The backend did not answer usably: an error, a refusal the contract does not name, or an answer that breaks the v1 schema. | Back off; retry with the same key. |
| `SERVICE_UNAVAILABLE` | 503 | gateway | Temporarily unavailable. | Back off; retry with the same key. |
| `NOT_FOUND` | 404 | gateway | The requested document, such as a schema name, does not exist. | Check the name. |
| `LEGACY_ENDPOINT_GONE` | 410 | gateway | A legacy `/v1/*` extractor endpoint after the go-live. | Update the client. |

## Per-op reasons in a change result

| Reason | Meaning | Client action |
| --- | --- | --- |
| `UNMATCHED` | The reference resolves to nothing. | Show it; offer `catalog/resolve`. |
| `AMBIGUOUS` | The reference resolves to several entries. | Ask the member to pick; send `bt`. |
| `DEFAULT_NOT_REMOVABLE` | A default-granted blueprint cannot be removed. | Keep it. |
| `STOCK_EARMARKED` | The lot's stock is reserved for a job order or mission. | Ask the member to release the reservation in the web. |
| `STOLEN_MARKING_DISABLED` | The Basetool does not yet keep stolen stock apart. | Keep the lot local until the server supports it. |
| `REMOVED_ELSEWHERE` | The entry has a live tombstone from another channel or installation; for a ship, the named `shipId` was removed there. | Ask the member; resend with `override: true` only if they agree. |
| `UNIT_MISMATCH` | The quantity's unit does not match the material's. | Fix the unit. |
| `LOCATION_UNKNOWN` | The place has no warehouse location. | Offer a place from `catalog/locations`. |
| `LINK_TARGET_TAKEN` | The server ship is already linked to another external id of this installation. | Pull and re-link. |
| `VERSION_CONFLICT` | As above, for this op only; also an `upsert` without `shipId` for an id this installation already linked. | Pull, merge, retry this op. |

## Warnings

A change result or resolve result may carry `warnings[]` with a JSON Pointer and a code. v1 defines
`UNKNOWN_FIELD` (the server ignored a field it does not know) and `LOC_KEY_UNRESOLVED` (no single
catalogue entry carries that name key; the name was tried instead).
