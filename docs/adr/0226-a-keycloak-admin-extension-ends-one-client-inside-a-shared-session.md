# ADR-0226 — A Keycloak admin extension ends one client inside a shared session

- **Status:** Accepted — owner decision 2026-09-27 (build the endpoint; the departure path gets the
  same Keycloak-first order).
- **Date:** 2026-09-27
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-008`) ·
  [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) (its
  amendment of 2026-09-27, which this completes) · [ADR-0055](0055-keycloak-spi-jar-as-promotable-oci-artifact.md)

## Context

A member's disconnect of an exchange client must end that client's Keycloak sessions. The consent
removal ends the offline sessions always and the online ones only when a consent existed, and the
stock Admin API ends an online session only whole (`DELETE sessions/{id}`, `POST users/{id}/logout`).
A device login joins the member's browser session, so the client usually shares it with the web
login: ending it whole would sign the member out of the Basetool. The ADR-0217 amendment therefore
deleted only sessions the client held alone and left shared ones to the gateway's `auth_time` check,
which leaves the client's online refresh token alive at Keycloak until the session ends.

Keycloak has exactly the operation needed — `AuthenticationManager.backchannelLogoutUserSessionFromClient`
detaches one client from one user session — but reaches it only through the consent path.

## Decision

1. **`keycloak-spi` adds an admin realm extension** (`AdminRealmResourceProvider`, id
   `basetool-exchange`): `DELETE /admin/realms/{realm}/basetool-exchange/users/{id}/clients/{client}/sessions`
   detaches the client from every online session of the member and revokes its offline sessions
   (`UserSessionManager.revokeOfflineToken`); the member's other clients keep their sessions. It
   records a Keycloak admin event (`ACTION` on `USER_SESSION`). An unknown member or client ends
   nothing; the method answers `204`.
2. **It is gated by `manage-users` over the member** (`auth.users().requireManage(user)`, or
   `requireManage()` when the member is unknown, so the answer reveals nothing without the right).
   That is the right that already allows ending the member's whole session, and `backend-service`
   holds it, so the extension widens no one's power and needs no new grant. A dedicated realm role
   would add a grant to provision and review without taking any power away from a `manage-users`
   holder.
3. **The backend calls it only for shared sessions.** `ConnectedAppsService` removes the consent,
   deletes the sessions the client holds alone (a later reconnect then signs in afresh and gets a new
   `auth_time`), calls the extension when a shared session remains, and only then stamps the
   revocation. A `404` means Keycloak runs an older SPI jar: the backend logs a warning and relies on
   the gateway's `auth_time` check, so a release whose backend reaches production before its SPI jar
   still disconnects. Any other failure fails the disconnect with `502` before anything is written.
4. **The departure path keeps the full logout** (`POST users/{id}/logout`): a departed member leaves
   every client, so the per-client end adds nothing there. It now removes the consents and logs the
   member out first and reads the revocation time afterwards, and still writes the revocations when
   a Keycloak step failed.

## Consequences

- A disconnected client's online refresh token fails at Keycloak at once („Session doesn't have
  required client"), also inside the web login's session; the gateway's `auth_time` check stays as
  the backstop for a Keycloak without the extension.
- The SPI jar gains a privileged HTTP surface inside Keycloak. It is small, needs an admin token with
  `manage-users` over the member, and is unit-tested for the gate and for leaving other clients alone.
- The SPI jar compiles against Keycloak internals (`AuthenticationManager`, `UserSessionManager`,
  the `fgap` permission evaluator); a Keycloak upgrade re-checks these signatures, as it already does
  for the Discord providers.

## Alternatives considered

- **Delete every session that holds the client.** Signs the member out of the web on each
  disconnect. Rejected.
- **Leave shared sessions to the gateway alone** (the ADR-0217 amendment). Works, but keeps the
  refresh token alive at Keycloak. Superseded for shared sessions by this decision.
- **Make every exchange client consent-required and rely on the consent removal.** Already the case
  for third-party clients and, with #2201, for the extractor; it does not help a client created
  without consent, and it changes nothing for the departure path.
