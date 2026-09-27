# Changelog

Changes to the Exchange API contract, newest first. Every change within `v1` is additive
([versioning](versioning.md)).

## 2026-09-27

- **Show the bare `verification_uri`.** A client shows the `user_code` and `verification_uri` and
  lets the member type the code; it no longer opens `verification_uri_complete`, which skips the
  page that warns about device-code phishing. The device response is unchanged.
- **`CLIENT_REVOKED` counts from the sign-in.** After the member disconnects a client, a token
  without `offline_access` is refused while its `auth_time` lies before the disconnect, also when it
  was refreshed afterwards; a client that requests `offline_access`, as it must, is unaffected.
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
