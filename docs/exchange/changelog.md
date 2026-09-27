# Changelog

Changes to the Exchange API contract, newest first. Every change within `v1` is additive
([versioning](versioning.md)).

## 2026-09-27

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
