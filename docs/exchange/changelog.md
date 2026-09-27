# Changelog

Changes to the Exchange API contract, newest first. Every change within `v1` is additive
([versioning](versioning.md)).

## 2026-09-27

- **Corrected: `Retry-After` per code.** The pages said every Redis failure answers
  `Retry-After: 60`. The gateway sends 30 with `EXCHANGE_DISABLED`, `REGISTRY_UNAVAILABLE` and a
  `SERVICE_UNAVAILABLE` whose daily write quota cannot be counted; 60 with
  `EXCHANGE_BUDGET_EXHAUSTED` and a `SERVICE_UNAVAILABLE` for a store it cannot reach; 5 when the
  identity provider cannot be reached ([errors](errors.md)). The behaviour is unchanged.
- **Clarified: the change result's `cursor` is reserved.** `change-result.schema.json` declares an
  optional `cursor`, which the server has never sent. It stays in the schema, since `v1` never
  removes a field, and is marked reserved; read the feed after a push for the new position.
- **Corrected: `UNAUTHENTICATED` is answered by a refresh.** The error registry said to start a
  device login again; as the authentication page says, refresh once, and start a device login only
  after the refresh answers `invalid_grant` and the member asks.
- **One held mass change per client.** A newer held batch replaces only your client's older one for
  that member; another client's held batch no longer displaces yours.
- **Show the bare `verification_uri`.** A client shows the `user_code` and `verification_uri` and
  lets the member type the code; it no longer opens `verification_uri_complete`, which skips the
  page that warns about device-code phishing. The device response is unchanged.
- **`CLIENT_REVOKED` counts from the sign-in.** After the member disconnects a client, a token
  without `offline_access` is refused while its `auth_time` lies before the disconnect, also when it
  was refreshed afterwards; a client that requests `offline_access`, as it must, is unaffected.
- **Store outages.** A store the exchange cannot reach answers `503 SERVICE_UNAVAILABLE` with
  `Retry-After: 60` on every exchange route. A lost Redis connection while staging a draft answered with the extractor
  upload's `Retry-After: 5`, and a store failure outside the staging could answer `500`.
- **Oversize drafts.** A draft or a held-back change set whose staged form exceeds the cap answered
  `400 BAD_REQUEST`, a code outside the registry, and the answer was replayed for its key. It now
  answers `413 PAYLOAD_TOO_LARGE` (draft) or `413 BATCH_TOO_LARGE` (change set), which is not
  cached.
- **Overlong unknown field names.** A request whose undeclared field has a JSON Pointer longer than
  200 characters is refused with `400 SCHEMA_INVALID` (`errors[]` names its parent) instead of being
  written and answered `502 BACKEND_RELAY_FAILED`.
- **Fixed `detail` texts.** A refusal the Basetool raises behind the gateway arrives with its code
  and a fixed English `detail` per code, no longer with the Basetool's own, possibly localised text
  ([errors](errors.md)).
- **OpenAPI document matches the gateway.** The `Idempotency-Key` takes 8 to 128 characters of
  `[A-Za-z0-9._~-]`; `POST /exchange/v1/me/installation` needs none; `catalog/resolve` and
  `catalog/locations` accept any exchange scope, `exchange.connect` included.
- **`VERSION_CONFLICT` at request level.** A write that meets a concurrent change the row locks did
  not already turn into a per-op `VERSION_CONFLICT` now answers `409 VERSION_CONFLICT` instead of
  `502 BACKEND_RELAY_FAILED`.
- **Drafts.** `POST /exchange/v1/me/drafts/blueprints` and `…/drafts/refinery-orders` stage a
  `basetool.blueprints` envelope or a refinery extract for the member's review and answer the
  `draft-result`. A draft too large to hand off is `413 PAYLOAD_TOO_LARGE`; a draft the Basetool
  refuses as malformed is `400 SCHEMA_INVALID` without `errors[]`.
- **Offline blueprint files.** The web's blueprint import reads the `basetool.blueprints` envelope.
- **Provenance.** A blueprint `add` records its `provenance.source`, and the blueprint feed answers
  it for every blueprint whose source was recorded.
- **Stock.** A change set that lowers a lot and raises its stolen or not-stolen twin marks the stock
  instead of booking it out and in; a lowered lot counts as moved for the mass-change guard only when
  the batch's rises of the same material cover all of it.

## 2026-09-26

- **v1 published.** The schemas, the OpenAPI document, the error registry and the conformance
  fixtures.
- **`installationId`** in the installation response and the service document — the value a
  tombstone's `removedBy.installationId` carries.
