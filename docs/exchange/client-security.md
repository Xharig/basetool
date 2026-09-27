# Client security requirements

Every approved client meets these requirements (REQ-XCH-027). They are checked when a client applies
(see [onboarding](onboarding.md)) and again for every release that changes how it signs in or stores
credentials.

## Sign-in

How each step works is on the [authentication](authentication.md) page.

- Use the OAuth 2.0 device authorization grant (RFC 8628) against the pinned production issuer
  `https://profit-base.online/auth/realms/iri`, with your registered public client id and no client
  secret.
- Show the `user_code` and the bare `verification_uri`, and let the member type the code in their
  browser. Never open, show or send `verification_uri_complete`, not even as a QR code: it skips the
  page where the Basetool warns about device-code phishing.
- Bind every token to a DPoP key (RFC 9449, ES256): prove possession at the token endpoint and on
  every API call, and retry once with the server's `DPoP-Nonce`.
- Pin the production issuer. Another issuer — the local sandbox — may be selected **only** through a
  developer environment variable, never in the user interface: a user-facing issuer switch would be
  a phishing lever.
- Request `exchange.connect` and only the scopes of the features the member enabled.
- Label each installation (`POST /exchange/v1/me/installation`) so the member can tell two devices
  apart and disconnect one of them. The label is at most 40 characters of letters, digits, spaces,
  `-`, `_` and `.`, and it is never an automatic host or computer name.

## Credential storage

- Keep the refresh token and the DPoP private key only in the platform's secret store: Windows
  Credential Manager or DPAPI, with the key non-exportable in CNG where available; on Linux the
  Secret Service, or — only where it is unavailable — a file readable by the user alone (`0600`)
  with a visible hint that it is used.
- Never write a token or a key into a log, a backup, a diagnostics bundle, a problem report or any
  channel that leaves the device. Redact `Authorization` and `DPoP` headers and anything shaped like
  a JWT from everything you collect.
- „Disconnect" revokes the refresh token (RFC 7009) and deletes the stored credential.

## Data

- Send only the signed-in member's own data, and never purchase data, account ids or other members'
  data. The RSI handle goes only to the account check.
- Store the org demand for at most 7 days, outside cloud-synced folders, never in backups or
  diagnostics, and never forward it.
- Send a descriptive `User-Agent`: product, version and a URL, for example
  `ExampleClient/1.2.0 (+https://example.org/client)`.

## Sync behaviour

The [sync guide](sync-guide.md) explains each rule; these are the ones an application is checked
against:

- Every resource is an explicit opt-in of the member and off by default.
- Pull before push; the first sync of a resource is add-only; a `remove` is sent only from a diff
  against the last synced state.
- Never re-add what the member removed elsewhere: a `REMOVED_ELSEWHERE` is shown to the member, and
  `override` is sent only after the member agreed.
- Ships are linked to the member's existing ships before any new one is created.
- After `410 CURSOR_EXPIRED`, reconcile a fresh snapshot against the last synced state; it is not an
  add-only first sync.
- Stock is sent as lots with the `expectedQuantity` last seen; a `VERSION_CONFLICT` is resolved by
  pulling, never by resending blindly.
- Before the first sync of a newly detected game account, run the account check and warn the member
  on `mismatch`.

## Response and supply chain

- Publish a privacy statement and a security contact.
- Publish a fixed release within **7 days** of a reported token-handling flaw; otherwise the client
  is suspended or restricted by a minimum version.
- Code signing of releases is recommended, not required.
