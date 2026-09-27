# Authentication

A client signs a member in with the **OAuth 2.0 device authorization grant** (RFC 8628), binds
every token to a key of its own with **DPoP** (RFC 9449, ES256), and sends every call with
`Authorization: DPoP <token>` and a fresh `DPoP` proof. This page describes each step exactly as
the gateway and the identity provider check it. The
[client security requirements](client-security.md) say how tokens and keys are kept; a
[reference implementation](dpop-reference/README.md) builds the proofs.

## At a glance

| | |
| --- | --- |
| Issuer (pinned) | `https://profit-base.online/auth/realms/iri` |
| Discovery | `https://profit-base.online/auth/realms/iri/.well-known/openid-configuration` |
| Device authorization endpoint | `https://profit-base.online/auth/realms/iri/protocol/openid-connect/auth/device` |
| Token endpoint | `https://profit-base.online/auth/realms/iri/protocol/openid-connect/token` |
| Revocation endpoint | `https://profit-base.online/auth/realms/iri/protocol/openid-connect/revoke` |
| Client | public, your registered client id, **no secret**, device grant only |
| Proof of possession | DPoP with ES256 (P-256) on every token request and every API call |
| Access token | lives **300 s**, audience `basetool-ingest` |
| Connection | ends after **30 days** without use and after **90 days** at the latest |

Take the endpoints from the discovery document, and use it only when its `issuer` is exactly the
pinned value. Another issuer — the local sandbox — may be chosen only through a developer
environment variable, never in the user interface ([client security](client-security.md)).

## Scopes

Request, space-separated:

- **`exchange.connect`** — always. It admits the service document, the installation label and the
  account check, and every exchange scope gives the token the gateway's audience; a token without
  one is refused `UNAUTHENTICATED`.
- **The capability scopes of the features the member enabled**, and no others — see
  [capabilities](README.md#capabilities). A capability the member turns on later needs a new device
  login with the larger set.
- **`offline_access`** — always. A device login joins the member's browser session; without an
  offline session, signing out of the Basetool in the browser would end the connection too.

Do not request `openid`, `profile` or `email`: the client receives no personal data. The token
response's `scope` lists what was granted. A route passes only when its scope is in the token
**and** the Basetool's registry grants it to your client; anything else is `403 SCOPE_MISSING`.

## Signing in

### 1. Open the installation's key

Load the DPoP key of this installation, or create it on first use (see
[installation identity](#installation-identity)). Every request below that carries a proof uses
this one key.

### 2. Ask for a device code

The device authorization request carries no proof; nothing is issued there.

```http
POST /auth/realms/iri/protocol/openid-connect/auth/device HTTP/1.1
Host: profit-base.online
Content-Type: application/x-www-form-urlencoded

client_id=example-client&scope=exchange.connect%20exchange.blueprints.read%20offline_access
```

The answer holds `device_code`, `user_code`, `verification_uri`, `verification_uri_complete`,
`expires_in` (600 s) and `interval` (5 s).

### 3. Let the member approve

Show the `user_code` and open `verification_uri_complete` in the member's browser. The member signs
in to the Basetool, or already is, confirms the code, and sees the consent page, in German, with one
line per requested capability. The consent page appears on **every** device login, also when the
member consented before.

Only ever show a code this installation created itself, and never relay a code to or from another
device: a device code typed into someone else's browser hands over that person's account (RFC 8628
§5.4). The Basetool's device page warns the member about it, and every new connection is announced
to the member.

### 4. Poll the token endpoint

Poll every `interval` seconds, each time with a **new** proof (`htm` `POST`, `htu` the token
endpoint, no `ath`):

```http
POST /auth/realms/iri/protocol/openid-connect/token HTTP/1.1
Host: profit-base.online
Content-Type: application/x-www-form-urlencoded
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwi...

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code&device_code=...&client_id=example-client
```

| Answer | Meaning | Next |
| --- | --- | --- |
| `400 authorization_pending` | The member has not decided yet. | Poll again after `interval`. |
| `400 slow_down` | Polling too fast. | Add 5 s to the interval for good. |
| `400 access_denied` | The member declined. | Stop; ask again only when the member starts it. |
| `400 expired_token` | The 600 s passed. | Start over at step 2. |
| `200` | Approved. | Continue with step 5. |

### 5. Keep the tokens

The answer carries `access_token`, `token_type`, `expires_in`, `refresh_token` and `scope`.
**`token_type` must be `DPoP`**; refuse any other answer, because an unbound token cannot use the
exchange. Both tokens are bound to your key (`cnf.jkt`). Store the refresh token in the platform's
secret store, never anywhere else ([client security](client-security.md)).

### 6. Label the installation

Call `POST /exchange/v1/me/installation` with a label the member chose, so they can tell two devices
apart and disconnect one of them.

## DPoP proofs

### The key

One **P-256** key per installation, created once and kept. Its private half never leaves the
platform's key store where the platform allows that — a non-exportable CNG key on Windows, in the
TPM where there is one. The refresh token is bound to it: a refresh with another key is refused, so
losing the key ends the connection.

### Building a proof

A proof is a JWS signed with the installation's key. Build a **new one for every request**, every
poll and every retry.

Header:

| Member | Value |
| --- | --- |
| `typ` | `dpop+jwt` |
| `alg` | `ES256` |
| `jwk` | the **public** key only: `kty` `EC`, `crv` `P-256`, `x`, `y` — never `d` |

Claims:

| Claim | Value | When |
| --- | --- | --- |
| `jti` | a unique random id, at least 96 bits; never reused — the gateway refuses a replayed one | always |
| `htm` | the request's method, such as `GET` or `POST` | always |
| `htu` | the request URL without query and fragment — see below | always |
| `iat` | the current time in whole seconds since the epoch | always |
| `ath` | base64url, without padding, of the SHA-256 of the access token's ASCII bytes | every API call |
| `nonce` | the server's latest `DPoP-Nonce` | every API call; at the token endpoint when Keycloak asks |

The signature is ECDSA P-256 over SHA-256 of `base64url(header) "." base64url(claims)`, in the JWS
form: `r` and `s` as two 32-byte big-endian halves, 64 bytes in all — **not** DER, which most
crypto libraries return and which has to be converted.

### `htu`

The gateway compares `htu` with `https://ingest.profit-base.online` followed by the request's path,
**as a plain string**. Write it the same way:

- scheme and host in lower case, no port;
- the path exactly as sent — the same percent-encoding, the same trailing slash;
- no query string and no fragment.

A request to `https://ingest.profit-base.online/exchange/v1/me/blueprints?cursor=f1.7.42` carries
the `htu` `https://ingest.profit-base.online/exchange/v1/me/blueprints`. At the token endpoint
`htu` is the token endpoint's URL.

### `iat` and clock skew

The gateway accepts an `iat` up to **30 seconds** before or after its own time. Keycloak is
stricter: from about 25 seconds before to 15 seconds after, and it checks the proof before the
grant, so a desktop clock that runs 15 seconds fast fails the sign-in itself. Measure each server's
offset from the `Date` header of its answers and correct `iat` per server. Retry a proof refused for
its time once with the corrected clock; if that fails too, ask the member to synchronise the clock.

### The server nonce

Every exchange call needs the gateway's **server nonce** (RFC 9449 §8).

- Answers that passed the token check carry `DPoP-Nonce`. Keep the latest one per server and put it
  into the next proof. Answers refused while the token or proof was checked carry none; keep the
  one you have.
- A proof without a current nonce is answered `401` with the code `DPOP_INVALID`,
  `WWW-Authenticate: DPoP algs="…", error="use_dpop_nonce"` and a fresh `DPoP-Nonce`. **Retry once**
  with a new proof carrying that nonce; a write keeps its `Idempotency-Key`.
- The nonce challenge comes before every other proof check. A proof that is also wrong in another
  way gets the challenge first and `DPOP_INVALID` with `error="invalid_dpop_proof"` on the retry —
  do not retry again.
- A nonce holds five to ten minutes; a restart of the gateway invalidates every nonce, which costs
  one retry.
- Nonces belong to one server. Never send the gateway's nonce to Keycloak. Keycloak issues none
  today; if it answers `400` with the error `use_dpop_nonce` and a `DPoP-Nonce`, retry once the same
  way.

### Calling the API

```http
GET /exchange/v1/me/blueprints HTTP/1.1
Host: ingest.profit-base.online
Authorization: DPoP eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJ...
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwi...
User-Agent: ExampleClient/1.2.0 (+https://example.org/client)
```

Send exactly one `Authorization` and one `DPoP` header. The `Bearer` scheme is refused
`401 DPOP_REQUIRED`, whatever the token.

## Refreshing

The access token lives 300 seconds. Refresh shortly before `expires_in` runs out, or once after an
`UNAUTHENTICATED`:

```http
POST /auth/realms/iri/protocol/openid-connect/token HTTP/1.1
Host: profit-base.online
Content-Type: application/x-www-form-urlencoded
DPoP: eyJ0eXAiOiJkcG9wK2p3dCIsImFsZyI6IkVTMjU2IiwiandrIjp7Imt0eSI6IkVDIiwi...

grant_type=refresh_token&refresh_token=...&client_id=example-client
```

The proof comes from the same key, without `ath`. When the answer carries a `refresh_token`, store
it in place of the old one.

- `400 invalid_grant` means the connection is over: the member disconnected the client, left the
  organisation, did not use it for 30 days, or reached the 90-day limit. Delete the stored refresh
  token and start a new device login only when the member asks for it.
- A refused proof — `400` with `invalid_dpop_proof` or `invalid_request` — says nothing about the
  refresh token. Keep the credential, fix the proof or the clock, and retry once.

## Disconnecting

„Disconnect" in the client does three things, in this order:

1. Revoke the refresh token (RFC 7009) at the revocation endpoint with `token`,
   `token_type_hint=refresh_token` and `client_id`, and a proof like at the token endpoint —
   Keycloak ignores the proof today, a stricter policy would require it.
2. Delete the stored refresh token.
3. Delete the DPoP key. A later connection creates a new key and so a new installation.

The revocation is best effort: delete locally whatever it answers.

The member can also disconnect in the Basetool, under „Verbundene Anwendungen": one installation,
or the whole client. The client learns it from the next call — `INSTALLATION_REVOKED` or
`CLIENT_REVOKED` — or from `invalid_grant` on the next refresh.

## Installation identity

An installation is one client on one PC. It is identified by the **RFC 7638 thumbprint of its DPoP
key**, which the token carries as `cnf.jkt`; the gateway takes the installation from the key, not
from anything the client says.

- The Basetool gives each installation an opaque `installationId` — never the thumbprint — in the
  installation response and the service document. A tombstone's `removedBy.installationId` names
  the installation that removed an entry, so a client recognises its own removals.
- **Keep the key.** A new key is a new installation: the member is notified of a new connection,
  and an unusual number of them alerts the Basetool's maintainers. Never create a key per session
  or per launch.
- A disconnected installation's key is refused for good (`INSTALLATION_REVOKED`), also with tokens
  issued afterwards. Delete the key and the tokens; reconnecting needs a new key.

## Errors

The gateway checks a request in this order: the token and the proof, the token's audience, the
exchange switch and the registry, the installation's key, the client revocation, the route's scope,
the minimum version; then the Basetool checks the member. Every refusal is an RFC 9457 problem with
a `code` from the [error registry](errors.md).

| Code | HTTP | When | Client action |
| --- | --- | --- | --- |
| `UNAUTHENTICATED` | 401 | No token; a token that is invalid, expired, or not issued for the gateway; a DPoP-scheme request without a `DPoP` header | Add the missing header; otherwise refresh once, and after `invalid_grant` start a device login when the member asks. |
| `DPOP_REQUIRED` | 401 | The `Bearer` scheme | Send `Authorization: DPoP` with a proof. |
| `DPOP_INVALID` with `use_dpop_nonce` | 401 | The proof lacks the current server nonce | Retry once with a new proof carrying the `DPoP-Nonce` of the answer. |
| `DPOP_INVALID` with `invalid_dpop_proof` | 401 | The proof is malformed, signed by another key than `cnf.jkt`, replayed, outside the `iat` window, for another method or URL, has the wrong `ath`, or the token is not DPoP-bound | Fix the proof; correct the clock once; do not loop. |
| `SCOPE_MISSING` | 403 | The route's capability is not in the token, or not granted to the client | Start a device login with the scope, if the member wants the feature. |
| `CLIENT_REVOKED` | 401 | The member disconnected the client after the token was issued | Discard the tokens; start a device login only when the member asks. |
| `INSTALLATION_REVOKED` | 401 | The member disconnected this installation | Discard the tokens **and** the key; reconnecting needs a new key. |
| `CLIENT_NOT_ALLOWED` | 403 | The client is not in the registry | Stop; the client is not approved. |
| `CLIENT_SUSPENDED` | 403 | The client is suspended | Stop and tell the member. |
| `CLIENT_VERSION_UNSUPPORTED` | 403 | The `User-Agent` version is below the client's minimum | Ask the member to update. |
| `TERMS_NOT_ACCEPTED` | 403 | The member has not accepted the current terms | Ask the member to open the Basetool and accept them. |
| `PENDING_APPROVAL` | 403 | The member's registration awaits approval | Stop; nothing to sync yet. |
| `NO_ROLE` | 403 | The member holds no role | Stop and tell the member. |
| `ACTING_MEMBER_REFUSED` | 403 | The member is unknown, disabled or deleted | Stop and tell the member. |
| `NOT_PERMITTED` | 403 | The member may not do this | Stop; do not retry. |

At the token endpoint Keycloak answers with OAuth errors, not problem documents:

| Error | When | Client action |
| --- | --- | --- |
| `authorization_pending`, `slow_down` | While polling | See [step 4](#4-poll-the-token-endpoint). |
| `access_denied` | The member declined | Stop. |
| `expired_token` | The device code expired | Start a new device login. |
| `invalid_grant` | The refresh token is no longer valid | Delete it; start a device login when the member asks. |
| `invalid_dpop_proof`, `invalid_request` | The proof was refused, often for its `iat` | Keep the credential; fix the proof or the clock and retry once. |
| `use_dpop_nonce` | The server wants a nonce | Retry once with the `DPoP-Nonce` of the answer. |

## Reference implementation

[`dpop-reference/`](dpop-reference/README.md) is a small, MIT-licensed Python package with no
dependency beyond the standard library. It keeps the key in Windows CNG or, on Linux, in an OpenSSL
key file with mode `0600`; computes the JWK thumbprint; signs ES256 in the JWS form; builds proofs
with `jti`, `htm`, `htu`, `iat`, `ath` and `nonce`; normalises `htu`; and remembers each server's
nonce. Its tests run offline with `python -m unittest`.
