> **Doc type:** Living spec — requirements accepted by the owner, built except where a status line
> says otherwise (epic [#2078](https://github.com/krt-profit/basetool/issues/2078)). Last reviewed:
> 2026-09-27.
> **Owner area:** XCH · **Related ADRs:** [ADR-0216](../adr/0216-the-exchange-api-is-a-separate-contract-on-the-ingest-gateway.md),
> [ADR-0217](../adr/0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md),
> [ADR-0218](../adr/0218-exchange-sync-semantics.md),
> [ADR-0219](../adr/0219-the-exchange-contract-grows-additively-under-a-path-major-version.md),
> [ADR-0220](../adr/0220-external-clients-see-only-anonymised-org-demand-and-the-location-list.md),
> [ADR-0221](../adr/0221-redis-grows-to-768-mb-and-holds-a-bounded-exchange-partition.md)

# External client exchange

## Context & goal

Approved external client software — VerseKit first, our own SC Extractor second — lets a member keep
blueprints, the personal part of the Lager and their ships in step between that program and the
Basetool, and read what their own units still need. It does so only through the **exchange API**
on the ingest gateway, never through the backend API. This spec states what must hold; the ADRs
above say why. The work is tracked in epic #2078; each requirement names the work package (WP) and
sub-issue that implements it.

> [!note] Each requirement's own status line is authoritative
> Each requirement carries its work package; its status line says what is built and what remains,
> and moves in the PR that lands the change, together with its **Enforced by** test. What remains
> is chiefly the sandbox (WP 2.3, #2099), the clients' migrations (WP 5.1, #2088; WP 5.2, #2089), the app (#2097) and
> the go-live (WP 6, #2092). Requirements of other specs that this one changes state it in their
> own text; a callout there marks only what is still planned. *Corrected 2026-09-27: this note said
> nothing was built yet, long after most of the requirements had landed.*

## Requirements

### REQ-XCH-001 — One exchange surface on the ingest gateway

The exchange API is served by the ingest gateway at `/exchange/v1/**`
(`https://ingest.profit-base.online/exchange/v1`). It offers exactly these routes:

| Method & path | Scope | Purpose |
| --- | --- | --- |
| `GET /exchange/v1` | `exchange.connect` | service document: API version, capabilities granted to this token, limits, deprecations, docs URL (the documentation site, `app.exchange.docs-url`, default `https://krt-profit.github.io/basetool/`), minimum client version |
| `GET /exchange/v1/openapi.json` | anonymous | the committed OpenAPI 3.1 document, served as a static file |
| `GET /exchange/v1/schemas/<name>.schema.json` | anonymous | the committed JSON Schemas, served at their `$id` (REQ-XCH-011) |
| `POST /exchange/v1/me/installation` | `exchange.connect` | label this installation (REQ-XCH-007) |
| `POST /exchange/v1/me/account-check` | `exchange.connect` | RSI-handle check (REQ-XCH-031) |
| `POST /exchange/v1/catalog/resolve` | any exchange scope | resolve item references (REQ-XCH-012) |
| `GET /exchange/v1/catalog/locations` | any exchange scope | the Lager's non-hidden locations (REQ-XCH-018) |
| `GET /exchange/v1/me/blueprints` · `POST …/changes` | `exchange.blueprints.read` / `.write` | REQ-XCH-015 |
| `GET /exchange/v1/me/stock` · `POST …/changes` | `exchange.stock.read` / `.write` | REQ-XCH-016 |
| `GET /exchange/v1/me/ships` · `POST …/changes` | `exchange.hangar.read` / `.write` | REQ-XCH-017 |
| `GET /exchange/v1/me/org-demand` | `exchange.demand.read` | REQ-XCH-018 |
| `POST /exchange/v1/me/drafts/blueprints` · `…/drafts/refinery-orders` | `exchange.drafts.blueprints` / `.refinery` | REQ-XCH-019 |

Every route is deny-by-default, uses only `GET` or `POST` (the ingest bot filter admits nothing
else), and no route path collides with a prefix or suffix the bot filter blocks. The backend's
exchange layer (`/api/v1/exchange/**`) is reachable only from the gateway's service identity; the
member controls (`/api/v1/connected-apps/**`) only from the member's own browser session. Neither
is ever on the `api.*` allowlist (ADR-0135), and nothing of the exchange lives under `/api/v1/me/`.

**Acceptance**

- [x] `IngestEndpointSurfaceTest` pins the exchange routes and methods; a test checks every route
  against `BotProtectionFilter`'s method, prefix and suffix lists (`ExchangeRouteBotCompatibilityTest`,
  every route of the committed OpenAPI document and every schema URL).
- [x] A test proves the gateway identity cannot reach `/api/v1/connected-apps/**`, and a browser
  session cannot reach `/api/v1/exchange/**` (`ConnectedAppsControllerTest`: the gateway and the app
  are refused; `ExchangeCatalogControllerTest`: an `ADMIN` browser session is refused, and so is the
  gateway without an acting member).
- [x] The `api.*` allowlist test fails if an exchange or connected-apps path is added
  (`ExternalContractTest.theExchangeStaysOffTheApiVhost`).

**Status:** built — WP 3.2 (#2082), WP 3.1 (#2083)

### REQ-XCH-002 — A client is approved publicly, for capabilities, case by case

A client is approved by a public issue plus a PR that adds it to `docs/legal/approved-clients.md`
(created with WP 4.6); the merge is the approval. The
criteria are applied case by case (closed source possible): token storage per REQ-XCH-027, DPoP
per REQ-XCH-006, a published privacy statement and a security contact. Code signing of releases is
recommended, not required. A reported token-handling flaw must be fixed within **7 days**, or the
client is suspended. The list document sits outside the terms' consent hash, so list changes
need no new consent (REQ-SEC-028).

**Acceptance**

- [ ] `docs/legal/approved-clients.md` exists, is linked from the terms clause (REQ-SEC-027) and
  lists client id, product, maintainer contact, capabilities and the approval issue and PR.
  *The list exists with these columns and no client yet; the terms clause links it at the go-live
  (WP 6). It records the approved capabilities, not the registry's runtime state.*
- [x] `docs/exchange/onboarding.md` states the criteria, the issue template and the fix deadline.
  *The template is `.github/ISSUE_TEMPLATE/exchange-client-application.yml`.*
- [ ] The privacy notice (the frontend's `privacy.*` keys, DE and EN) states which data flows to an
  approved client on the member's own device, that the client's own privacy statement governs it
  there, and how to disconnect and undo; it changes with the go-live.

The third-party pages are `docs/exchange/`, English, published on GitHub Pages at
`https://krt-profit.github.io/basetool/` by `.github/workflows/exchange-docs.yml`: on every change to
the pages, the OpenAPI document or the schemas it checks that each relative link stays on the site
and resolves (`check_exchange_docs_links.py`), lints the Markdown, renders the OpenAPI document into a
static reference from the committed schemas (`prepare_exchange_reference.py`, Redocly), copies the
document and the schemas beside it and builds the site with Jekyll; a pull request builds, only
`main` deploys, and only the deploy job holds `pages: write` and `id-token: write`.

**Status:** the list, the onboarding page, the application template and the documentation site with
its overview, formats, errors, versioning, changelog and authentication pages, and the MIT-licensed
DPoP reference `docs/exchange/dpop-reference/` (stdlib Python, CNG and OpenSSL 3 through `ctypes`,
its tests run by `exchange-docs.yml`), are built — WP 4.6 (#2090); the resource pages, the sync
guide, the quick start and the sandbox page follow; the terms link and the privacy notice change with the go-live — WP 6 (#2092)

### REQ-XCH-003 — The client registry lives in the backend database and is mirrored fail-closed

The registry (`exchange_client`: client id, display name, status `ACTIVE`/`SUSPENDED`, granted
capabilities, minimum client version, rate-limit overrides, contact URL, `version`) and the global
exchange switch are stored in the backend database and managed by `ADMIN` only. The backend
mirrors them into Redis under `exchange:*` with a version and a timestamp, rewrites the mirror on
startup and reconciles it every 60 s. Restrictive changes (suspend, capability removal, global
switch off, revocations) are written to Redis **before** the database commit and fail the action if
the mirror write fails; permissive changes are written **after** the commit. The gateway reads the
mirror through a cache of at most 5 s and refuses every exchange request when it cannot read it
(`503 REGISTRY_UNAVAILABLE`) or when the switch is off (`503 EXCHANGE_DISABLED`).

**How the backend keeps the mirror** (WP 3.1). The tables are `exchange_client`,
`exchange_client_capability` and the single-row `exchange_settings` (`V248`); the switch starts
**off**. The mirror is **one JSON document** under `exchange:registry`:
`{schemaVersion: 1, revision, writtenAt, enabled, clients: {<clientId>: {displayName, status,
capabilities[], minClientVersion, requestsPerMinute, writesPerDay}}}`, the capabilities as their
scope strings, sorted. `revision` comes from the database sequence `exchange_registry_revision_seq`,
so it grows across restarts. Every registry change and every mirror write first takes the row lock
on `exchange_settings`, so a mirror write never overtakes an open change. A change that takes
access away writes the **restrictive combination** of before and after (switch on only if on in
both, a client suspended in either is suspended, capabilities intersected, a new client left out)
before its commit; a failed write fails the change with `502` and changes nothing. After the
transaction completes — committed or rolled back — the committed state is written again; a failure
there is counted and left to the reconcile, which compares the content (not `revision` and
`writtenAt`) at startup and every 60 s (`app.exchange.mirror.reconcile-interval`) and rewrites a
differing, missing or unreadable document. The mirror is written only while
`APP_EXCHANGE_MIRROR_ENABLED=true`; while it is off nothing is mirrored and the gateway, which then
finds no document, refuses every exchange request.

**Acceptance**

- [x] Tests for both write orders, a failed mirror write, the reconcile healing a divergence, and
  the gateway's fail-closed read. *Backend: `ExchangeRegistryMirrorIntegrationTest` against a real
  Redis under the backend's ACL user, and `ExchangeRegistrySnapshotTest` (WP 3.1). Gateway:
  `ExchangeRegistryReaderTest` — a missing document, an unknown `schemaVersion`, garbage and an
  unreachable Redis all fail closed, a failed read is not cached — and `ExchangeGateTest`, which
  answers them `503 REGISTRY_UNAVAILABLE` with `Retry-After` (WP 3.2).*
- [x] Every registry change writes an audit event in „Verbundene Anwendungen" and fires the
  `ExchangeRegistryChanged` alert.
- [x] The admin page *Administration → Verbundene Anwendungen* (`/admin/exchange-clients`)
  registers, edits, suspends and activates clients and flips the switch in place; suspending, either
  direction of the switch and granting a client more capabilities each ask for confirmation first.
  *`AdminExchangeClientsPageControllerMvcTest`, `AdminExchangeClientsE2eTest`.*
- [x] Each client shows its connected members and last activity, counted over live installations
  only (`GET /api/v1/admin/exchange-clients/usage`: not revoked, and not seen last before the
  member disconnected the client); the error rate per client is linked in Grafana
  (`APP_GRAFANA_OPERATIONS_DASHBOARD_URL`, owner decision 2026-09-27). *`AdminExchangeClientUsageTest`,
  `AdminExchangeClientsPageControllerMvcTest`.*

**Enforced by:** `ExchangeRegistryMirrorIntegrationTest`, `ExchangeRegistrySnapshotTest`,
`AdminExchangeRegistryControllerTest`, `AdminExchangeClientsE2eTest`, `RedisAclBackendIntegrationTest`,
`RedisAclIngestIntegrationTest`, `monitoring/prometheus/tests/exchange_registry_alerts_test.yml` ·
**Code:** `ExchangeRegistryService`, `ExchangeRegistryMirrorSync`, `RedisExchangeRegistryMirror`,
`ExchangeRegistryReconcileTask`, `AdminExchangeRegistryController` · **Status:** registry, admin
API and mirror built — WP 3.1 (#2083); the gateway's read — `ExchangeRegistryReader`, a
five-second cache (`app.exchange.registry-cache-ttl`) of the `app.exchange.registry-key` document —
built with WP 3.2 (#2082); the admin page built — WP 4.5 (#2087)

### REQ-XCH-004 — Capabilities are OAuth scopes, enforced at the gateway and re-checked at the backend

The capabilities are `exchange.connect` (base: service document, installation label, account
check), `exchange.blueprints.read` / `.write`, `exchange.stock.read` / `.write`,
`exchange.hangar.read` / `.write`, `exchange.demand.read`, `exchange.drafts.blueprints` and
`exchange.drafts.refinery`. A request passes only if the route's scope is in the token **and**
granted to the client in the registry (`403 SCOPE_MISSING`); the backend's exchange layer checks
the relayed capabilities again (`@PreAuthorize("@exchangeGate.allows(…)")`). Each scope stamps the
`basetool-ingest` audience, and the gateway enforces that audience on exchange routes in code, not
only by property.

**Acceptance**

- [x] Gate tests for a scope missing from the token, a scope not granted in the registry, and a
  wrong audience with the audience property blank. *The gateway requires `aud` ∋ `basetool-ingest`
  on exchange routes in code (`ExchangeDpopGateTest`); a route passes only when its capability is
  both in the token and granted in the registry, and a route for "any exchange scope" needs at
  least one such capability (`ExchangeGateTest`). `ExchangeRoutes` holds the route table,
  `ExchangeRoutesContractTest` pins it to the committed OpenAPI document route for route and scope
  for scope, and a path or method it does not list is `404 NOT_FOUND`.*
- [x] ArchUnit: every exchange controller method carries the exchange gate
  (`ArchitectureTest.everyExchangeControllerMethodCarriesTheExchangeGate`).

**How the backend checks** (WP 3.1). `@exchangeGate.allows('<scope>', authentication)` — or
`allowsAny(authentication)` for a route any exchange scope serves — passes only an acting member
relayed for an external client whose authorities hold `ROLE_EXCHANGE_MEMBER`, while the global
switch is on, the client is in the registry and `ACTIVE`, and the scope was both relayed (the
`XCH_CAPABILITY:<scope>` authority) and granted to the client. Every refusal is counted as
`basetool_exchange_gate_refused_total{reason}`.

**Status:** built — the scopes (WP 2.2, #2081), the registry's per-client grants
(`ExchangeCapability`, WP 3.1) and the backend's `ExchangeGate` (WP 3.1, #2083), and the gateway
check (`ExchangeGateFilter`, WP 3.2, #2082)

### REQ-XCH-005 — Every third-party client is a public, consent-gated device-grant client

Each product has its own public Keycloak client: device grant only, `consentRequired`,
`fullScopeAllowed` off, no PII protocol mappers and none of the realm's default `profile`, `email`
or `roles` scopes, `exchange.connect`, `offline_access` and every capability scope optional, the
device code living 600 s at a pinned polling interval, and `dpop.bound.access.tokens` on. Clients
request `offline_access`, because a device login joins the member's browser SSO session and a web
logout would otherwise disconnect every client (owner decision 2026-09-26). Consent is shown in German, per capability. The consent and device
pages use the Basetool theme; the device page warns to enter only codes created on one's own PC.
The clients are created by `scripts/provision-keycloak-realm.py`, never by hand.

The first-party SC Extractor is held to the same shape once it has migrated (security finding H1,
owner decision 2026-09-27): its client `basetool-sc-extractor` requires consent, binds access and
refresh tokens to DPoP, carries only `basic` by default and offers only its exchange scopes and
`offline_access`. It loses both ingest scopes, so no extractor token carries `aud=basetool-backend`
any more; before, a phished device code yielded an unbound, refreshable bearer token the backend API
accepted. The provisioner applies this on production only **after** the legacy switch-off
(REQ-XCH-033), because released extractors up to 2.9.1 still need `extractor-ingest-only` on `/v1/*`.

**Acceptance**

- [x] The provisioner's self-test covers the third-party template (withheld scopes removed from an
  existing client too, 30/90-day offline session, owner decision 2026-09-26) and the SC Extractor's
  exchange scopes (`scripts/provision-keycloak-realm.test.sh`, sections 13–15). The extractor
  requests `offline_access` too and gets the same 30/90-day offline session pinned on its client
  (owner decision 2026-09-27).
- [ ] The extractor client loses `extractor-ingest` once the extractor has migrated (WP 5.1 / go-live).
  *The provisioner half is built: `basetool-sc-extractor` requires consent, has DPoP-bound tokens,
  only `basic` by default and withholds both ingest scopes and every non-exchange scope; section 16 of
  the self-test converges a client in today's production shape to it. The box closes with the
  production apply after the legacy switch-off (WP 6, #2092).*
- [x] The theme renders both pages with the phishing warning (`login-oauth-grant.ftl`,
  `login-oauth2-device-verify-user-code.ftl`).
- [x] Keycloak 26.7.4's behaviour is observed (WP 0.4, 2026-09-26, a throwaway local Keycloak of the
  pinned image, owner decision to observe locally): a device login joins the browser SSO session
  (same `sid`); a web logout ends it and the next refresh fails `invalid_grant` unless the client
  holds an offline session; removing the consent removes the client from the session, or deletes
  its offline session, at once; an admin logout makes offline tokens stale; the device flow shows
  the consent page on every login, also when consent exists; access and refresh tokens carry
  `cnf.jkt`, and a refresh without a DPoP proof is refused.

**Status:** behaviour observed — WP 0.4; template, scopes and theme pages — WP 2.2 (#2081); the extractor's `extractor-ingest` removal — WP 5.1

### REQ-XCH-006 — DPoP is required on every exchange route

A request to an exchange route without a valid DPoP proof bound to the token's `cnf.jkt` is refused
(`401 DPOP_REQUIRED` / `401 DPOP_INVALID`). The legacy `/v1/*` routes keep today's behaviour
(`REQ-INGEST-012`) until they end (REQ-XCH-033).

Spring's proof verifier checks `htm`, `htu`, `iat` (30 s skew), the binding to `cnf.jkt`, `ath` and a
replayed `jti`. On exchange routes the gateway also requires a **server nonce** (RFC 9449 §8): a
proof without a current one is answered `401 DPOP_INVALID` with `WWW-Authenticate: DPoP …,
error="use_dpop_nonce"` and a fresh `DPoP-Nonce`, and the client retries once with it. Every exchange
response carries the current nonce. A nonce is stateless — a five-minute window and its HMAC under a
key drawn at startup — and holds for its window and the next; a restart invalidates them all, which
costs a client one retry. A bearer-scheme request, or a token without `cnf.jkt`, is `401
DPOP_REQUIRED` with the DPoP challenge.

**Acceptance**

- [x] Tests for a bearer token, an unbound token, a proof for another key, a replayed proof and a
  missing nonce, and that the retry with the nonce passes (`ExchangeDpopGateTest`,
  `ExchangeDpopNoncesTest`).

**Enforced by:** `ExchangeDpopGateTest` · **Status:** built — WP 3.2 (#2082)

### REQ-XCH-007 — Installations are identified by their DPoP key and labelled by the client

An installation is one client on one PC, identified by the thumbprint of its DPoP key. A client
labels it with `POST /exchange/v1/me/installation {label}`. The label has at most 40 characters of
letters, digits, space, `-`, `_` and `.`, is never logged and never written to audit details, and
is always shown after the registered client name. The backend keeps `exchange_installation`
(client, member, thumbprint, label, first and last seen) and gives each installation an opaque id —
never the thumbprint — which the installation response and the service document return, so a
client recognises its own removals in a tombstone's `removedBy.installationId` (asked by the VerseKit
author, owner decision 2026-09-26).

**How it is built** (WP 3.1). The gateway relays the verified key thumbprint as
`X-Exchange-Installation` (honoured like the other relay headers; an exchange call without a
well-formed one is refused as `exchange_installation_invalid`). The backend creates
`exchange_installation` on first sight, moves `last_seen_at` forward at most every five minutes after
each admitted exchange request, and serves `GET` / `POST /api/v1/exchange/me/installation` (the
opaque `installationId`, never the thumbprint). Leading and trailing spaces and controls are trimmed
by the global JSON normalisation; what is left must match the schema's rule after NFC. Homoglyph-only
labels are letters and are accepted: the label is always shown after the registered client name.

**Acceptance**

- [x] Label validation tests, including control, bidi and homoglyph-only input
  (`ExchangeInstallationControllerTest`).
- [x] Log-capture test: the label never appears in any log line (`ExchangeInstallationControllerTest`).
- [ ] The installation response and the service document carry the same `installationId`, and a
  tombstone written by that installation names it. *The gateway half is in: `POST
  /exchange/v1/me/installation` checks the label against `installation.schema.json` before the relay
  (a rule-breaking label never reaches the backend), and the service document takes
  `installationId` from the backend's installation of the relayed key (`ExchangeControllerTest`).*

**Status:** gateway routes built — WP 3.2 (#2082); the backend's installations with WP 3.1 (#2083),
tombstones with WP 3.3

### REQ-XCH-008 — Revocation takes effect on the next request

Disconnecting **one installation** puts its key thumbprint on a persistent deny list (database,
mirrored to Redis, kept at least as long as a client session can live — 90 days, ADR-0217 amendment); every token bound to that
key is refused (`401 INSTALLATION_REVOKED`) whatever its `iat`, and reconnecting needs a new key.
Disconnecting **a whole client** removes the member's Keycloak consent for it (for a first-party
client without consent: ends its client and offline sessions) and stores a revocation timestamp per (client,
member); a token issued before it is refused (`401 CLIENT_REVOKED`), and a new connection afterwards
works at once. When a member leaves the org (disabled, deleted, membership lost), their exchange
sessions and consents end — an admin logout, which also makes offline tokens stale — and
revocations are written at once, not at the next roster sync. The
gateway reads the deny list and the timestamps per request, bypassing its cache.

**How it is built** (WP 3.1). A revoked installation row is the deny-list entry for its key; a
member's disconnect of a whole client is a row in `exchange_client_revocation` (V249). Both reach the
Redis mirror before the commit — `exchange:deny:<thumbprint>` and
`exchange:revoked:<clientId>:<member>`, each holding the revocation's epoch second and expiring 90 days
after it — and a failed write fails the disconnect with `502`. Disconnecting a client also removes the
member's Keycloak consent for it, which revokes its offline tokens. The 60-second reconcile writes
back any enforced entry the mirror lacks. The backend's `@exchangeGate` refuses a revoked installation
itself (`installation_revoked`). The member's controls are `/api/v1/connected-apps` (list,
`DELETE /{clientId}`, `DELETE /installations/{id}`), reachable only from the member's own web session.

**Acceptance**

- [ ] A revoked installation is refused after a token refresh; another installation of the same
  client keeps working. *Backend: `ExchangeInstallationControllerTest`,
  `ExchangeRevocationMirrorIntegrationTest`. Gateway: it reads `exchange:deny:<jkt>` on every
  request, bypassing its cache, and refuses a listed key `401 INSTALLATION_REVOKED` whatever the
  token's `iat` (`ExchangeGateTest`). The end-to-end run follows with the sandbox (WP 2.3).*
- [ ] A revoked client is refused, and a fresh connection right after works. *The gateway half is
  in: it reads `exchange:revoked:<client>:<member>` per request and refuses a token issued at or
  before that second `401 CLIENT_REVOKED`, while a token issued after it passes
  (`ExchangeGateTest`).*
- [ ] A departed member is refused on the next request. *The backend half is in (WP 3.1): the roster
  sync and the login sync publish `MemberDepartedEvent` when an active member is disabled, loses
  every role or disappears from Keycloak, and `ExchangeDepartureService` then — after the sync's
  commit, only while the registry holds a client — writes a revocation for every client (mirror and
  database), removes the member's consent for each and logs them out of every session, auditing
  `EXCHANGE_MEMBER_DEPARTED`; a failed step is counted and alerts (`ExchangeDepartureIncomplete`)
  instead of failing the sync (`ExchangeDepartureIntegrationTest`, `UserReconciliationServiceTest`).
  The gateway refuses the member through the per-client revocations those steps write
  (`ExchangeGateTest`); the end-to-end run follows with the sandbox (WP 2.3).*

**Status:** built — WP 3.1 / 3.3 (#2083), WP 3.2 (#2082), WP 4.5 (#2087); the end-to-end run
follows with the sandbox (WP 2.3, #2099)

### REQ-XCH-009 — The acting member holds a reduced authentication and sees own data only

On `/api/v1/exchange/**` the acting member holds an exchange role and the relayed capability
authorities — never their stored roles, permissions or contextual grants. Exchange reads and
writes touch only the member's own blueprints, own personal Lager rows and own ships; they never
use the admin all-scope or an admin pin. The membership, pending-approval and terms gates apply
unchanged. No exchange response carries personal data of
anyone.

**How it is built** (WP 3.1). `ActingMemberFilter` keeps an explicit list of exchange routes
next to the two ingest routes. On an exchange route the member gets
`ActingMemberAuthorities.exchangeAuthoritiesFor`: `ROLE_EXCHANGE_MEMBER` plus one
`XCH_CAPABILITY:<scope>` per relayed known scope, and nothing else; a member the approval or role
gate refuses keeps exactly that gate's marker, so the gates refuse as they do for the web. The
demand feed reads the member's memberships itself (`ExchangeDemandService`, REQ-XCH-018).
*Corrected 2026-09-27: this requirement said the acting member would also hold the memberships the
demand feed needs, arriving with it; the feed was built without any membership authority.*

**Acceptance**

- [ ] An `ADMIN` member reads and writes only own rows and holds no admin authority on exchange
  paths. *The authority half is in (`ExchangeCatalogControllerTest`: an `ADMIN` member's exchange
  request holds only `ROLE_EXCHANGE_MEMBER` and the relayed capabilities); the own-rows half is
  proven by each read and write route as it ships (WP 3.3).*
- [x] ArchUnit: exchange services never call an admin-gated method or the admin scope predicate;
  exchange controllers call exchange services only; exchange DTOs stay in the exchange layer
  (`ArchitectureTest`).

**Status:** relay and reduced authentication built — WP 3.1 (#2083); the data routes built with
WP 3.3 and WP 4.1–4.4

### REQ-XCH-010 — The relay names the external client, and only the gateway may

The gateway relays under ADR-0129 (service account plus `X-Ingest-On-Behalf-Of`) and adds
`X-Exchange-Client` and `X-Exchange-Capabilities`. The backend honours both only from the
gateway's service identity and refuses and counts them from any other caller. The acting
authentication carries the external client, so audit rows and client metrics name it (for example
`versekit`) instead of `none`; the known-client vocabulary comes from the registry. This attribution
is live before the first registry entry exists.

**How it is built** (WP 3.1). `X-Exchange-Client` / `X-Exchange-Capabilities` are honoured only
when the gateway acts for a member on an exchange route; from anyone else — or from the gateway on
an ingest route — the request is refused with `403 ACTING_MEMBER_REFUSED` and counted as
`basetool_on_behalf_of_refused_total{reason="forged_exchange_header"}`, and an exchange call without
a well-formed client as `exchange_client_invalid`. The acting authentication carries the client;
`ClientAttribution` names it when the registry holds it (else `other`), for the audit row and —
read from the header before the identity swap, for the gateway only — for
`basetool_api_client_requests_total`. The audit viewer offers the registry's clients by their
product names.

**Acceptance**

- [x] Forged-header tests from a browser session and from the app (`ActingMemberFilterChainTest`).
- [x] An exchange write's audit row carries the external client id. *`ClientAttributionTest`;
  `ExchangeBlueprintWriteControllerTest`, `ExchangeStockWriteControllerTest` and
  `ExchangeShipWriteControllerTest` read the client from the written audit rows.*

**Status:** built — WP 3.1 (#2083)

### REQ-XCH-011 — The v1 data formats are published JSON Schemas

The formats are JSON Schema 2020-12 files. Their source is
`ingest/src/main/resources/exchange/v1/schemas/`; the gateway serves each one anonymously at its
permanent `$id`, `https://ingest.profit-base.online/exchange/v1/schemas/<name>.schema.json` (owner
decision 2026-09-26), and a `$id` is never changed once published. The schemas are:
`item-ref` (precedence `bt` › `scRecord` › `scGuid` › `uexId` › `locKey` › `name` + `nameLocale`),
`quantity` (`{amount, unit: SCU|PIECE}`, SCU ≤ 3 decimals, PIECE whole), `quality` (integer
0–1000; trade goods fixed 0), `location-ref`, `provenance` (`log|manual|import|default|other`,
`observedAt`), `material-kind` (`RAW|REFINED|NO_REFINE` plus `commodity`), `blueprint`, `stock-lot`
(material, location, quality, `stolen`, quantity — no org unit, no row id), `ship` (with required
`version`), `org-demand`, `location`, `installation`, `account-check`, `change-set` (at most 500
ops), `change-result` (compact, at most 32 KiB), `page`, `service-document`, `problem` and the
offline-file `envelope` (`format`, `formatVersion`, `generator`, `generatedAt`, `items`,
`extensions`; no handle, player, source folder or file path). One OpenAPI 3.1 document,
`ingest/src/main/resources/api/exchange-v1.openapi.json`, is authoritative for the exchange routes.

**Acceptance**

- [x] CI validates every conformance fixture in `docs/exchange/examples/v1/` against its schema, and
  every schema the OpenAPI document names exists and has valid and invalid fixtures.
- [x] A test fails when a served route and the OpenAPI document diverge: `ExchangeRoutesContractTest`
  holds the gate's route table equal to the document, route for route and scope for scope, and
  `IngestEndpointSurfaceTest` fails for any served exchange route outside that table.

The gateway checks each request body against its schema before relaying it and answers a violation
`400 SCHEMA_INVALID` with `errors[]` (JSON Pointer and the violated keyword, at most 50); it checks
the backend's answer against the response schema too, and an answer that breaks it is `502
BACKEND_RELAY_FAILED`, never passed on (`ExchangeSchemas`, `ExchangeSchemasTest` over every
conformance fixture, `ExchangeControllerTest`). The validator is `com.networknt:json-schema-validator`,
the library the contract test already used, without its YAML module.

The gateway serves both anonymously and unchanged, with `Cache-Control: public, max-age=3600`:
`GET /exchange/v1/openapi.json` and `GET /exchange/v1/schemas/<name>.schema.json` (as
`application/schema+json`; an unknown name is `404 NOT_FOUND`). The extractor's own OpenAPI document
does not list them.

**Enforced by:** `ExchangeContractTest`, `ExchangeDocumentsControllerTest` · **Status:** schemas,
OpenAPI document and fixtures committed and validated — WP 0.2 (#2080); served by the gateway since
WP 3.2 (#2082)

### REQ-XCH-012 — Names resolve through the web import's own matching

`catalog/resolve` answers `resolved`, `ambiguous` or `unmatched` per reference. `scRecord` is
compared case-insensitively with `blueprint.scwiki_key`, which is not unique; `scGuid` is matched
against blueprint records and output items, and duplicates resolve `ambiguous`. Names resolve
through `BlueprintImportService.resolve()` — REQ-INV-006, REQ-INV-019, REQ-INV-021, REQ-INV-050 —
never through a second logic. `locKey` is compared case-insensitively with the catalogue's
`name_key` — the `global.ini` name key without its `@`, which the P4K import stores for items,
materials and ship types (migration `V250`; `LOC_` placeholders are not stored), and which a
blueprint takes from its output item; a `locKey` that resolves to no single entry falls through to
the name with a warning. Places resolve against the Lager's `location` table; a
place without a row is `LOCATION_UNKNOWN`.

A reference takes the first of its fields, in the order `bt`, `scRecord`, `scGuid`, `uexId`,
`locKey`, `name`, that resolves to exactly one entry; if none does, the first that resolved to
several is the answer, so an ambiguous `scRecord` still yields to a name that resolves. `ambiguous`
lists at most ten candidates as `{bt, name}`; fuzzy suggestions are always `ambiguous`, even when
there is only one, because they are never taken without the member. `LOC_KEY_UNRESOLVED` points at
`/refs/<i>/locKey` of every reference that carries a `locKey` and whose keys, `locKey` included, did
not resolve; a response carries at most 50 warnings. Per catalogue:

| `kind` | `bt` | `scRecord` | `scGuid` | `uexId` | `locKey` | `name` |
| --- | --- | --- | --- | --- | --- | --- |
| `BLUEPRINT` | the product key (normalized output name) | `blueprint.scwiki_key` | the blueprint's Wiki or game-file UUID, or its output item's | the output item's UEX id | the output item's `name_key` | the web import's chain: exact, alias, pack-tag strip, fuzzy |
| `ITEM` | the item id | `class_name` | Wiki or game-file UUID | UEX item id | `name_key` | exact, case-insensitive; duplicates `ambiguous` |
| `MATERIAL` | the material id | `scwiki_key` | Wiki or game-file UUID | UEX commodity id | `name_key` | exact, canonical (`MaterialNameCanonicalizer`), external alias, fuzzy — visible materials only |
| `SHIP_TYPE` | the ship type id | `class_name` | Wiki UUID | UEX vehicle id | `name_key` | the hangar import's `ShipTypeMatcher` |

The backend answers on `POST /api/v1/exchange/catalog/resolve` for any exchange capability; the
gateway reports unknown request fields as `UNKNOWN_FIELD` warnings (WP 3.2).

**Acceptance**

- [x] The anonymised corpus fixture
  (`backend/src/test/resources/fixtures/blueprint-corpus/game-log-corpus-v1.json`) resolves the same
  through the exchange and through the web import (`ExchangeResolveCorpusTest`).

**Enforced by:** `ExchangeResolveCorpusTest`, `ExchangeResolveServiceTest`,
`ExchangeResolveControllerTest` · **Status:** backend built — WP 3.1 (#2083); served by the gateway
with WP 3.2 (#2082)

### REQ-XCH-013 — Each resource has a snapshot and a database-sequenced change feed

`GET /exchange/v1/me/<resource>` returns a snapshot or, with `cursor`, the changes since it. The
feed is sequenced at the database level, so writes that bypass the services — default-grant
provisioning, „delete all", admin purge, user deletion, org re-stamping, owner reassignment and a
change of the default blueprint set — appear in it. Removals leave tombstones with `removedBy`
(`web`, `app`, `client` with client id and installation id, `system`) and `removedAt`, kept 90 days
and purged nightly. A cursor older than the tombstones answers `410 CURSOR_EXPIRED`.

The sequence is `exchange_change` (ADR-0224): an `AFTER` row trigger on every synced table records
`(member, resource, key)` with its writing transaction's id and a sequence number, and the feed reads
each changed key's current state, or a tombstone when it is gone. A feed position is `(transaction id,
seq)`, and a reader passes only transactions below the oldest one still running, so an entry committed
late can never land behind a position a client has already passed. Who wrote it comes from the transaction variable
`basetool.change_source`, which the backend's transaction manager sets at the start of every writing
transaction (`web`, `app`, `client|<id>|<installation key>`, otherwise `system`). A nightly job
(`exchange_change_retention`, 03:30 UTC) purges entries older than 90 days and records the highest
purged position as the horizon, below which a cursor has expired.

**Acceptance**

- [x] A test fails for any write path to the synced tables that bypasses the sequence.
  *`ExchangeChangeFeedTriggerIntegrationTest` pins the synced tables — `personal_blueprint`,
  `default_blueprint`, `inventory_item` (the member's personal rows only, keyed by lot) and `ship` —
  to their triggers and runs bulk deletes, owner reassignment, a rebooking to the shared pool and
  user deletion through them.*
- [x] A default-set change emits entries for every affected member.
  *`ExchangeChangeFeedTriggerIntegrationTest`.*
- [x] Every writing transaction is attributed to its channel. *`ChangeSourceTransactionManagerIntegrationTest`.*
- [x] A writer that commits after a later one stays ahead of the readers' watermark.
  *`ExchangeChangeWatermarkIntegrationTest`.*

A snapshot pages by row id and ends with the feed cursor it was taken at, so nothing written during
it is lost. A feed page answers each key changed after the cursor once, with its current state or a
tombstone whose `installationId` is the removing installation's id. A feed page that reaches the end
moves the cursor up to the watermark, so an idle client's cursor never falls behind the horizon.
Cursors are `s1.<tx>.<seq>.<id>` and `f1.<tx>.<seq>` and stay opaque to clients; one the server did not
issue also answers `CURSOR_EXPIRED`.

Once an exchange write has committed, the backend raises the live-sync frames the member's web
pages listen on, so they refresh without a reload: `hangar:{member}` after a ship write,
`blueprints:{member}` after a blueprint write, `inventory` after a stock write and `materialboard`
when that write lowered or removed an offer (REQ-FE-015). A rolled-back write raises none.

**Status:** sequence, attribution and retention built for blueprints, stock and ships — WP 3.3
(#2083); the blueprint, stock and ship feeds and their gateway routes built — WP 4.1 (#2084), WP 4.2
(#2085), WP 4.4 (#2086)

### REQ-XCH-014 — A client never re-adds what the member removed elsewhere

An `add` of an entry that has a live tombstone is refused per op with `REMOVED_ELSEWHERE`,
whichever installation or channel removed it. A client may send `override: true` only after asking
the member; the override is journaled.

**Acceptance**

- [x] One installation removes, another tries to re-add: refused; with override: applied and
  journaled.

A tombstone is live while the key's latest change-log entry — its removal — is within the
retention; the same installation may re-add what it removed itself. *`ExchangeBlueprintWriteControllerTest`
also covers a removal in the web.*

**Status:** built for blueprints, stock and ships — WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086)

### REQ-XCH-015 — Blueprints sync as a set

Ops are `add` and `remove` of products. Default-granted blueprints cannot be removed
(`DEFAULT_NOT_REMOVABLE`). A blueprint's `note` is read-only in v1. Writes are audited in the
Blueprints domain with the external client. An `add` records the client and the source its
`provenance` names, and the feed publishes a recorded source as `provenance.source` (REQ-INV-054).

A blueprint's `key` and its `ref.bt` are the same value: the normalised product key, or `h:` and
its SHA-256 in hex when that is longer than 128 characters. The display name is cut to 200. The
resolver (REQ-XCH-012) answers a blueprint with the same `bt` and accepts it back.

**Acceptance**

- [ ] Round trip: the corpus fixture added through the exchange appears in „Meine Blueprints" and
  in the feed of another installation.
- [x] The feed marks default-granted blueprints and follows a change of the default set.
  *`ExchangeBlueprintControllerTest`.*

The backend applies a change set at `POST /api/v1/exchange/me/blueprints/changes`
(`exchange.blueprints.write`) in one transaction: it resolves every reference in one resolver call,
plans the ops in order — an add of an owned product and a remove of a missing one are `unchanged`, a
remove of a default `rejected DEFAULT_NOT_REMOVABLE`, an add against another's tombstone `rejected
REMOVED_ELSEWHERE` — then asks the mass-change guard, and writes through the web's own add and
delete, so the Blueprints audit names the client; each written entry is journaled. `dryRun` plans
only. `basetool_exchange_writes_total{resource,outcome}` counts the ops.

**Status:** read and write sides built in the backend, and the gateway's read route (`GET
/exchange/v1/me/blueprints`) and write route (`POST …/changes`) — WP 4.1 (#2084); the corpus round
trip follows with the sandbox (WP 2.3)

### REQ-XCH-016 — Stock syncs as lots, booked like the web

A lot is material + location + quality + stolen over the member's personal rows, across org-unit
pools. `set-quantity` carries `expectedQuantity`; the server compares under row locks and answers
`409 VERSION_CONFLICT` on a difference, otherwise books the delta in or out through the Lager's
services. Book-ins carry no org unit (REQ-ORG, own stamping path); book-outs take rows without a
unit first, then the oldest. Linked Materialbörse offers follow a book-out as in the web; the result
reports `offersReduced` and `offersRemoved`, and each change writes its `MARKET_*` audit event.
Trade goods are stored at quality 0. Writes are audited in the Lager domain with the external
client.

**Acceptance**

- [x] Concurrent `set-quantity` on one lot: one applies, the other gets `VERSION_CONFLICT`.
  *`ExchangeStockWriteControllerTest`: the lot's row locks serialise the two, and the second finds
  the quantity changed. An optimistic-lock failure a write meets anyway reaches the client as
  `409 VERSION_CONFLICT`, not as a relay failure.*
- [x] A book-out below an offered amount lowers the offer and records the audit event.
  *`ExchangeStockWriteControllerTest`.*
- [x] A lot sums the member's personal rows across pools, leaves shared rows out, and becomes a
  tombstone when its rows are gone or rebooked to the shared pool. *`ExchangeStockControllerTest`.*
- [x] Moving stock to the lot's stolen twin marks the rows — a part split off, a whole lot flipped —
  and a piece book-in joins the existing row. *`ExchangeStockWriteControllerTest`.*

The feed's lot key is the one the change log records, `m:<material>|l:<location>|q:<quality>|s:<0|1>`
or `i:<item>|…`; a snapshot pages lots by their lowest row id. The material reference carries the
material's or item's id as `bt`, an item lot has quality 0 and counts whole pieces, and an SCU amount
is rounded to three decimals. Game items stay in the stock sync beside materials (owner decision
2026-09-27).

The backend applies a change set at `POST /api/v1/exchange/me/stock/changes`
(`exchange.stock.write`) in one transaction. Each op resolves its material — a material first, an
item otherwise — and its place, the UEX link first, then the exact name of a non-hidden location
(`LOCATION_UNKNOWN`), checks both units against the material's (`UNIT_MISMATCH`), locks the lot's
rows and compares `expectedQuantity` (`VERSION_CONFLICT`). A trade good is stored at quality 0. A
lot emptied by another channel or installation is refilled only with `override`
(`REMOVED_ELSEWHERE`); stock reserved for a job order or mission is never taken (`STOCK_EARMARKED`,
owner decision 2026-09-27 — personal rows carry no reservations, so this guards the invariant);
a stolen lot waits for `APP_INVENTORY_STOLEN_MARKING_ENABLED` (`STOLEN_MARKING_DISABLED`). The
mass-change guard counts a lot set to 0 or cut to a tenth of what it held when the client's window
opened, unless the batch's rises of the same material cover the whole fall (REQ-XCH-021). A fall and
a rise of the lot's stolen or not-stolen twin — same material, place and quality — are a marking,
not a book-out and a book-in: as much as both allow is marked through the Lager's own marking
(REQ-INV-053, a part split off as a new row, `INVENTORY_STOLEN_MARKED` / `_UNMARKED`), leaving
out rows backing a Materialbörse offer and the earmarked part of a row; the rest is booked (owner
decision 2026-09-27). A book-in is a new personal row without an org unit
(`INVENTORY_ITEM_CREATED`), which piece-counted stock then joins to the existing row as a book-in in
the Lager does (REQ-INV-026, owner decision 2026-09-27); a book-out runs the Lager's own
`DISCARD` book-out over the rows without an org unit first, then the oldest, and every offer it
lowers or removes is audited by that book-out (`MARKET_OFFER_REDUCED`, `MARKET_OFFER_REMOVED`,
`reason=stock`, REQ-MARKET-013) and counted in
`offersReduced` / `offersRemoved`. Each changed lot is journaled.

**Status:** read and write sides built in the backend, and the gateway's read route (`GET
/exchange/v1/me/stock`) and write route (`POST …/changes`) — WP 4.2 (#2085)

### REQ-XCH-017 — Ships sync with a link step before the first create

`link` attaches a client ship to an existing server ship; `upsert` and `remove` carry the ship's
`version`. A client links before it creates, so a Fleetview import is never duplicated. Purchase
data is never sent. Detaching a ship from a mission by removal is reported in
`detachedFromMissions` and audited (`MISSION_UNIT_UPDATED`). Writes are audited in the Hangar domain
with the external client. A ship's `name` is optional and up to 255 characters, as in the web: an
unnamed ship is sent without it, and an upsert may leave it out (owner decision 2026-09-27). An
`upsert` sets the ship as sent: a `name` or `location` it leaves out is cleared, and only an absent
`fitted` keeps its value. A ship a client creates belongs to the member's only direct org unit, or to
none when the member has none or several, because a client names no unit; the member assigns it
later in the web, as a stock book-in (owner decision 2026-09-27, `HangarService.addShipForClient`).
*Corrected 2026-09-27: a member of several units had the whole batch refused with
`OWNER_ORG_UNIT_REQUIRED`.*

**Acceptance**

- [x] First sync against a Fleetview-imported hangar creates no duplicate.
  *`ExchangeShipWriteControllerTest`.*
- [x] The feed carries the member's own ships only, without purchase data, and answers a ship given
  to another member as a tombstone. *`ExchangeShipControllerTest`.*

The ship feed keys a ship by its id and sends its `version`, the ship type's id as `shipType.bt`,
insurance as `LTI` or a number of months, and the location when it has one. A ship stored without
insurance, which the web's validation does not allow, reads as zero months.

The backend applies a change set at `POST /api/v1/exchange/me/ships/changes`
(`exchange.hangar.write`) in one transaction; the feed carries each ship's `externalId` for the
calling installation. A link belongs to one **installation** (owner decision 2026-09-27): each
installation links its own ids, so two installations with separate local databases never mistake
each other's ids, and a server ship is linked at most once per installation (`LINK_TARGET_TAKEN`);
re-linking an id moves it. `link` needs the member's own ship; `upsert` without `shipId` creates the
ship through the Hangar's own create and links it, unless the id is already linked, which answers
`VERSION_CONFLICT` so the client pulls first; `upsert` with `shipId` requires the ship's `version`
(`VERSION_CONFLICT`), writes through the Hangar's own update and links the id if it is not yet. An
`upsert` naming a ship the server no longer has brings it back as a new ship only when the calling
installation removed it or with `override` after asking the member (`REMOVED_ELSEWHERE`, owner
decision 2026-09-27); a ship the member never had is `unmatched`. `remove` requires the `version`,
detaches the ship from its mission units through the Hangar's delete (`MISSION_UNIT_UPDATED`) and
reports the count as `detachedFromMissions`. The ship type resolves through `catalog/resolve`, the
place like a stock lot's; an absent `fitted` keeps the ship's. Every write is audited in the Hangar
area with the client and journaled.

**Status:** built in the backend, and the gateway's read route (`GET /exchange/v1/me/ships`) —
WP 4.4 (#2086), and its write route (`POST …/changes`)

### REQ-XCH-018 — Org demand is anonymised and membership-scoped; locations are the non-hidden list

`GET /exchange/v1/me/org-demand` lists the open demand of the units the member belongs to, never
units the member merely oversees or administers: `materials[]` (material, `rawRefs[]`, open
quantity per `minQuality`, `source: material-order|item-order`) and `items[]` (item, open quantity,
`craftableByMe`), plus `updatedAt`. It carries no requester, assignee, order title, free text or
per-order breakdown and no low-count suppression; a client may cache it for up to 7 days.
`GET /exchange/v1/catalog/locations` lists the non-hidden `location` rows with their UEX link.

The backend serves the location list as `GET /api/v1/exchange/catalog/locations` (any exchange
scope), one query, a city link winning over a space-station link.

The backend serves the demand as `GET /api/v1/exchange/me/org-demand` (`exchange.demand.read`): the
open and in-progress orders a unit of the member's memberships is responsible for. A material line
is the gap per material, quality floor (650 for „gut", else 0) and source, computed exactly as the
Materialbedarf computes it: required and booked are summed over the orders of one responsible unit,
each rounded to the material's precision, and the difference is clamped at 0 once — so stock booked
beyond one order's need offsets another order's gap in that unit — then the units are added up
(owner decision 2026-09-27; `ExchangeDemandParityTest`). *Corrected 2026-09-27: the feed clamped
per order and rounded the difference, and so disagreed with the Materialbedarf in both cases.* `rawRefs` are the materials whose refined material it is. An item
line sums `max(0, ordered − delivered − earmarked)` per game item, and `craftableByMe` matches the
member's blueprints the way the order's blueprint coverage does (variant family when the order counts
variants). Lines with nothing open are left out; `bt` is the material's or game item's id.

**Acceptance**

- [x] An overseer who is not a member of a unit does not see its demand.
  *`ExchangeDemandServiceTest` — only the member's own units are asked.*
- [x] The feed's open quantities equal the Materialbedarf's gaps for the same orders.
  *`ExchangeDemandParityTest`.*
- [x] The response schema admits no name or free-text field.
  *`ExchangeOrgDemandRouteTest` pins the schema's field sets; the only names are catalogue names.*

**Status:** the backend location list is built — WP 3.1 (#2083); the backend's demand and the
gateway's demand route (`GET /exchange/v1/me/org-demand`) are built — WP 4.3 (#2095)

### REQ-XCH-019 — Drafts keep review-before-commit

`drafts/blueprints` and `drafts/refinery-orders` stage an upload for review in the browser, as
REQ-INGEST-004 requires today; nothing is written until the member confirms.

**Acceptance**

- [ ] The SC Extractor's draft flows pass unchanged through the exchange routes.
- [x] A draft is checked against its schema, relayed, staged and answered with its handoff; a
  refused one stages nothing. *`ExchangeDraftRouteTest`.*
- [x] The backend previews a blueprint draft as an upload would and writes nothing; each draft
  needs its own capability. *`ExchangeDraftControllerTest`.*

The gateway checks a draft against `blueprint-draft.schema.json` or `refinery-draft.schema.json`
(`SCHEMA_INVALID`) and relays it to `POST /api/v1/exchange/me/drafts/blueprints` or
`…/refinery-orders` (`exchange.drafts.blueprints` / `exchange.drafts.refinery`). The backend builds
exactly what the extractor's upload builds: for blueprints it resolves each `ref` as
`catalog/resolve` does and previews a resolved ref under its product's name and any other under the
name sent — or its first key when it has none — so it lands among the unmatched rows for a manual
pick; repeats collapse to the earliest `acquiredAt`. For refinery orders it is the refinery import's
draft. A draft the backend refuses as malformed is `400 SCHEMA_INVALID`. The gateway stages the
answer in the member's extractor draft slots (`HandoffKind.BLUEPRINT` / `REFINERY`, at most
`app.ingest.max-handoff-bytes`, a larger one `413 PAYLOAD_TOO_LARGE`), counts it against the
exchange's byte budget and answers `draft-result` with the `frontendUrl` of the blueprint import
review or the refinery create form. As write routes they take an `Idempotency-Key` and count
against the daily quota.

The web blueprint import reads the same `basetool.blueprints` envelope as an upload, so a client's
offline file and its draft end in the same review (owner decision 2026-09-27, REQ-INV-014).
*Corrected 2026-09-27: the owner's answer of 2026-09-26 kept the envelope to the draft route; it was
revisited once the SC Extractor was to write its offline files in that format.*

**Status:** built in the backend and the gateway — WP 3.2 (#2082); the extractor's side is WP 5.1
(#2088)

### REQ-XCH-020 — Writes are idempotent per client and member

Every write carries an `Idempotency-Key` (`400 IDEMPOTENCY_KEY_MISSING`), kept 24 h and keyed per
(client, member, key). Authentication, gates and rate limits run before the lookup; only results
produced after them are cached — never `401`, `403`, `429`, `503`,
`MASS_CHANGE_CONFIRMATION_REQUIRED` or a `5xx`. A duplicate in flight gets
`409 IDEMPOTENCY_IN_PROGRESS`; a reused key with a different body `422 IDEMPOTENCY_KEY_REUSED`.

The key is 8 to 128 characters of `[A-Za-z0-9._~-]` and is stored only as a hash, under
`ingest:xch:idem:<client>:<member>:<sha256>`; a request's fingerprint is the SHA-256 of method, path
and body. The same request under a known key is answered from the cache with `Idempotency-Replayed:
true`. Cached are the answers `2xx`, `400`, `404`, `409`, `410` and `422`, and never a staged mass
change. The lock of a key in flight lives two minutes, so a crashed request cannot block a key for
the day. A store Redis cannot reach is `503 SERVICE_UNAVAILABLE`, never an unguarded write.

**Acceptance**

- [x] Replay, cross-member key, in-flight duplicate, uncached `429`. *The gates, limits and quota run
  before the cache, so a refused request never reaches it (`ExchangeIdempotencyFilterTest`: replay,
  reused key, in-flight duplicate, the cached and uncached statuses, a store that fails; the namespace
  holds the client and member, so one member's key can never answer another's).*

**Enforced by:** `ExchangeIdempotencyFilterTest`, `ExchangeStoreRedisIntegrationTest` · **Status:**
built — WP 3.2 (#2082)

### REQ-XCH-021 — Mass changes are confirmed by the member in the browser

Per client, member and resource over a rolling 24 h, a batch that takes the window above 25
removals, or above 20 % of (current count + entries removed in the window) with at least 5, is
staged and answered with `MASS_CHANGE_CONFIRMATION_REQUIRED` and a confirmation URL under
„Verbundene Anwendungen". Removals are `remove`, a quantity set to 0, a lot's reductions
accumulated to ≥ 90 % within the window, and a ship update that changes name and type; a move
within one batch is not a removal. Only the member's browser session can confirm.

**Acceptance**

- [ ] One test per counting rule, including repeated 89 % cuts and a move.

The counting rule is `ExchangeMassChangeGuard`: over the journal's live removals of the client,
member and resource in the last 24 hours plus the batch's, a batch trips above 25, or when that total
is at least 5 and more than a fifth of the current count plus the window's removals. Each resource's
write service decides what in its batch is a removal.

A stock lot counts as removed when it is set to 0 or cut to at most a tenth of what it held when the
client's window opened, taken from the lot's first journal entry in the window. A fall is a move and
does not count when the batch's rises of the same material or item still cover all of it, the falls
taken in the batch's order — a single piece added elsewhere does not hide an emptied lot (owner
decision 2026-09-27).

A ship counts as removed by `remove`, and by an `upsert` that changes both its name and its type.

When the backend answers `MASS_CHANGE_CONFIRMATION_REQUIRED`, the gateway stages the change set with
its client, installation and resource in the handoff staging (`HandoffKind.MASS_CHANGE`, one slot
per member apart from the extractor drafts, at most `app.exchange.store.max-mass-change-bytes`,
512 KiB, counted against the exchange's Redis budget) and answers `409` with a `confirmationUrl` to
`/connected-apps/confirm?handoff=<id>`. A change set too large to hold is `413 BATCH_TOO_LARGE`.

The confirmation link opens `/connected-apps/confirm?handoff=…`. As ADR-0110 requires, loading the
page consumes nothing: its script strips the id from the address bar and consumes the staged batch
with an explicit request, after which the batch waits in the member's server session and the
browser names it only by its handoff id, so it cannot alter the batch or its client. The backend
checks again what the gateway checked — the global switch, the client active with the write
capability, the client not disconnected by the member within the staging lifetime (30 minutes),
the installation not disconnected — then previews the batch as a dry run and, on „Bestätigen",
applies it without asking the guard again, in one transaction recorded in the change log as the
installation's own write and audited as `EXCHANGE_MASS_CHANGE_CONFIRMED`
(`POST /api/v1/connected-apps/mass-changes/preview|confirm`, member session only). „Verwerfen"
drops it; a batch confirmed or dropped once is gone.

**Status:** built — WP 3.3 (#2083), WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086), WP 3.2 (#2082),
WP 4.5 (#2087)

### REQ-XCH-022 — Every exchange write is journaled and can be undone

Each exchange write is journaled for 90 days. The member can undo a client's writes since a point
in time from „Verbundene Anwendungen"; undo is version-checked, skips and reports rows the member
changed afterwards or a merge removed, and does not restore Materialbörse offers.

**Acceptance**

- [x] Undo after a later web edit skips that row and reports it. *`ExchangeUndoControllerTest`.*

The journal is `exchange_journal`: one row per written entry with the client, installation, change
set, resource, key, action, whether it counts as a removal, the entry before and after as JSON, the
writing transaction's id and the time. It is written in the write's own transaction, purged with the
change feed after 90 days by `exchange_change_retention`, exported under Art. 15, stays with the
source account on a merge, and its states are searched by the Personensuche.

The member undoes from „Verbundene Anwendungen" with `POST /api/v1/connected-apps/{clientId}/undo
{since}` (member session only), reaching back at most 90 days. Each entry the client wrote in the
span goes back to its state before the client's first write there — a blueprint added or removed, a
lot set back through the Lager's own book-in and book-out, a ship deleted, updated back or recreated
under a new id without its mission units — unless the entry's latest change-log entry is not the
client's last write, then it is skipped as `CHANGED_AFTERWARDS`; one that no longer belongs to the
member or names something gone is skipped as `GONE`. Links the client made are taken back. The
restored entries' journal rows are marked undone, the undo is audited as `EXCHANGE_CHANGES_UNDONE`
(restored and skipped counts) and counted in `basetool_exchange_undo_total{resource,outcome}`, and
the member's pages refresh live.

**Status:** journal and undo built — WP 3.3 (#2083), WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086),
WP 4.5 (#2087)

### REQ-XCH-023 — Rate limits, quotas and a hard Redis budget

Per-minute buckets per (client, member) and per client run in-process; daily write quotas live in
Redis (`ingest:xch:quota:*`). All gateway-written exchange data in Redis is bounded to 1 MiB per
client and member, 16 MB per client and 64 MB in total, counted exactly; above a limit the gateway
answers `503 EXCHANGE_BUDGET_EXHAUSTED`. A batch holds at most 500 ops (`413 BATCH_TOO_LARGE`).
Responses carry `RateLimit` and `Retry-After` headers. The account check has its own tight limit.

**The limits** (owner decision 2026-09-27; `app.exchange.limits.*`):

| Limit | Default | Where |
| --- | --- | --- |
| requests per client and member | 120 per minute — a registry client's `requestsPerMinute` overrides it | in-process bucket |
| requests per client, over all its members | 1200 per minute | in-process bucket |
| account checks per client and member | 10 per hour | in-process bucket |
| write requests per client and member | 500 per UTC day — `writesPerDay` overrides it | Redis, `ingest:xch:quota:<client>:<member>:<day>`, `INCR`, kept two days |

The write routes are the five `…/changes` and `…/drafts/…` routes (`ExchangeRoutes`). A request over
a per-period limit is `429 RATE_LIMITED`, over the quota `429 QUOTA_EXCEEDED`, each with
`Retry-After` (the quota's until the next UTC day); a quota that cannot be counted is `503
SERVICE_UNAVAILABLE` with `Retry-After: 30`, never a free pass. Every admitted answer carries
`RateLimit-Policy: <limit>;w=60` and `RateLimit: limit=…, remaining=…, reset=…` for the member's
bucket. The in-process buckets live per gateway instance and are bounded (least recently used out).

**The byte budget** (`app.exchange.store.*`): every value the gateway stores for the exchange
registers `<key>|<bytes>` in three sorted sets — `ingest:xch:budget:m:<client>:<member>`,
`…:c:<client>` and `…:all` — scored by its expiry, and expired entries are pruned before each count,
so the count falls as keys expire. Before a write runs, the gateway reserves the largest cacheable
answer (32 KiB) against all three budgets and refuses with `503 EXCHANGE_BUDGET_EXHAUSTED` when one
would overflow, so a full budget stops writes before they reach the backend. The quota counters (from
their first write of the day) and the idempotency locks (while a write is in flight) count too.
`basetool_ingest_exchange_budget_used_ratio`
reports the total's use; `ExchangeBudgetHigh` fires above 80 %.

**Acceptance**

- [x] A load test fills one member's budget, then one client's; sessions and other members keep
  working. *`ExchangeStoreRedisIntegrationTest` fills one member's and then one client's budget in a
  real Redis under the ingest ACL user; other members and clients keep fitting, and expired entries
  free their bytes. Sessions live under keys the ingest user cannot reach at all.*

**Status:** built — WP 3.2 (#2082); the production Redis size and ACL follow with the go-live,
WP 2.1 (#2092)

### REQ-XCH-024 — A minimum client version can be enforced

The registry holds a minimum version per client. A request whose `User-Agent`
(`<Product>/<semver> (+url)`) names an older version is refused with
`403 CLIENT_VERSION_UNSUPPORTED`. The gate is cooperative: it stops honest old releases, not a
client that lies.

A missing or unparseable `User-Agent` counts as older, and a pre-release of the minimum itself
(`2.4.0-beta.1` against `2.4.0`) is below it, while build metadata (`2.4.0+7`) is not.

**Enforced by:** `ClientVersionsTest`, `ExchangeGateTest` · **Status:** built — WP 3.2 (#2082)

### REQ-XCH-025 — Errors are problem+json with a stable code

Every error is RFC 9457 problem+json with a `code` from the registry in `docs/exchange/errors.md`,
each with its HTTP status and the client action it requires. Codes are never reused or repurposed;
the gateway-side codes are the `reason` labels of the exchange metrics.

**Enforced by:** `ExchangeContractTest` (the registry's codes are unique and carry error
statuses) · **Status:** registry published — WP 0.2 (#2080); the gateway's refusal metrics carry
the codes as `reason` labels (`ExchangeRefusals`) — WP 3.2 (#2082)

### REQ-XCH-026 — The contract grows additively under `/exchange/v1`

Within `v1` only additive changes are allowed; a breaking change is `v2`, served in parallel for at
least 12 months with `Deprecation` and `Sunset` headers. Readers are tolerant both ways (unknown
fields ignored and reported as `warnings`, unknown enum values `UNKNOWN`), published schemas stay
open, extensions are namespaced, identifiers and cursors are opaque (ADR-0219).

The gateway finds the fields a request body carries that its schema does not declare — at any
depth, following `$ref`, `allOf`, `anyOf` and `oneOf`, and leaving objects that accept any field
(`extensions`) alone — and reports each as an `UNKNOWN_FIELD` warning with its JSON Pointer in answers
that carry `warnings` (the resolve and change results); elsewhere they are ignored.

**Acceptance**

- [x] A contract test fails a change that removes or narrows anything in a v1 schema: CI copies
  the latest release's schemas to `ingest/build/exchange-baseline/` and
  `ExchangeContractTest.theSchemasOnlyGrewSinceThePreviousRelease` compares them with
  `SchemaCompatibility`, whose rules `SchemaCompatibilityTest` pins. Until a release carries the
  v1 schemas the comparison has nothing to compare and is skipped.

**Enforced by:** `ExchangeContractTest`, `SchemaCompatibilityTest` · **Status:** implemented —
WP 0.2 (#2080)

### REQ-XCH-027 — Approved clients meet the client security requirements

A client stores tokens only in the platform's secret store (Windows Credential Manager / DPAPI;
Linux Secret Service, with a `0600` file fallback and a visible hint), keeps the DPoP private key
non-exportable where the platform allows, never writes a token into logs, backups, diagnostics or a
problem-report channel, pins the production issuer and allows another only through a developer
environment variable, and sends a descriptive `User-Agent`. It also syncs as the sync guide
requires: each resource an opt-in, pull before push, an add-only first sync, removals only from a
diff, no re-add of what the member removed elsewhere without asking, ships linked before created,
and the account check before a new game account's first sync. The checklist is
`docs/exchange/client-security.md`; the application template asks for each point.

**Status:** the checklist `docs/exchange/client-security.md` is written — WP 4.6 (#2090); the
clients' implementations with WP 5.1 (#2088), WP 5.2 (#2089)

### REQ-XCH-028 — The exchange is observable per client

The gateway and the backend export per-client request, error, write and budget metrics under
bounded labels (REQ-OBS-011), alert on a registry change, on 80 % of the Redis budget and on error
spikes per client, and a blackbox probe checks `GET /exchange/v1` for `401`. The nightly purge of
tombstones and journal reports task metrics.

- [x] The gateway's refusal and relay counters carry a `client_id` bounded by the registry, the
  operations dashboard shows them per client, and `ExchangeClientRefusalsSpike` alerts on one client's
  refusals outside its own limits. *`ExchangeRefusalsTest`, `ExchangeGateTest`,
  `exchange_gateway_alerts_test.yml`.* The admin page links there instead of showing an error rate
  itself (owner decision 2026-09-27).
- [x] Registry changes and the Redis budget alert (`ExchangeRegistryChanged`, `ExchangeBudgetHigh`).
- [x] A blackbox probe checks `GET /exchange/v1` for exactly `401` (`blackbox-http-401`, module
  `http_401`; `BlackboxProbeFailed` covers it).
- [x] Per-client write metrics with the WP 3.3 journal, and the tombstone and journal purge task
  metrics. *The writes, undo, confirmation, removal and installation counters carry `client_id`;
  `ExchangeRemoveSpike`, `ExchangeGuardStorm`, `ExchangeInstallationSurge` and `ExchangeUnknownClient`
  alert on them (`exchange_write_alerts_test.yml`); `exchange_change_retention` purges feed and
  journal under `ScheduledJobStale`.*
- [x] Every gateway log line of an exchange request carries the registry-bounded client label and
  the route template (`exchangeClientId`, `exchangeRoute`; Loki structured metadata `client_id`,
  `route`). *`ExchangeGateTest`, `CorrelationIdFilterTest`.*
- [x] `basetool_exchange_clients{status}` counts the registry clients per status from the snapshot
  the mirror sync reads, without a query per scrape. *`ExchangeClientGaugesTest`,
  `ExchangeRegistryMirrorIntegrationTest`.*
- [x] `basetool_exchange_registry_mirror_age_seconds` is the time since the gateway's last good read
  of the mirror, which it reads every 30 s; `ExchangeRegistryMirrorStaleAtGateway` alerts above
  5 minutes while the mirror is enabled. The document's `writtenAt` is not used, because the backend
  rewrites the mirror only on a change. *`ExchangeRegistryReaderTest`,
  `exchange_mirror_age_alerts_test.yml`.*
- [x] A dedicated Grafana dashboard „Exchange" (`15-exchange.json`) shows all of the above per
  client, with the gateway's log lines filtered by client.
- `basetool_ingest_gate_enforcing` is not extended to the exchange gates, since they cannot be
  switched off (owner decision 2026-09-27).

**Status:** built — WP 3.3 (#2083), #2091 (the monitoring extras of 2026-09-27 included); the
runbooks live in the knowledge base

### REQ-XCH-029 — Third parties get a local sandbox

Public sandbox images and a `sandbox` compose profile run the ingest gateway, the backend and a
Keycloak realm with a test client and seeded data on a developer's machine. The sandbox issuer is
`http://host.docker.internal:18080/auth/realms/iri`. No production credential or artefact enters it.

The sandbox is `docker-compose.sandbox.yml` on the test stack, started, reset and stopped by
`scripts/sandbox.sh` / `scripts/sandbox.ps1`, with the committed throwaway values of
`docker/sandbox/sandbox.env` and the committed test TLS. Its realm is generated by
`scripts/build-sandbox-realm.py` from the E2E realm and the production provisioner's scopes, realm
settings, ingest gateway client and third-party template (`sandbox-client`,
`sandbox-suspended-client`, synthetic members with fixed ids), and `repo-lint.yml` fails when the
committed realm is stale. A one-shot `sandbox-seed` applies `docker/sandbox/seed.sql` after the
backend is healthy, idempotently. It publishes loopback ports only
([`docs/exchange/sandbox.md`](../exchange/sandbox.md)).

- [x] Profile, provisioned realm, seed, one command and the docs page — WP 2.3 part 1.
- [x] The public sandbox images, their publishing pipeline and secret scan.
      *`.github/workflows/sandbox-images.yml` builds `basetool-sandbox-{backend,frontend,ingest}`
      from the unchanged `docker/app/Dockerfile` plus the marker `docker/sandbox/SANDBOX`
      (`docker/sandbox/app.Dockerfile`) and `basetool-sandbox-keycloak` from
      `docker/sandbox/keycloak/Dockerfile`; before publishing it proves each application image
      refuses the `prod` profile (`SandboxProfileGuard`, which fails the start of any image carrying
      the marker under `prod`) and fails on any secret Trivy finds. It publishes `edge` when run by
      hand on `main` and the version and `latest` on a release tag; the production packages stay
      private and untouched. The packages' public visibility is set once by the owner.*
- [x] The CI job that pulls them anonymously and runs the conformance fixtures and the
      device-grant + DPoP smoke test.
      *`.github/workflows/sandbox-smoke.yml` runs after every publish (called by
      `sandbox-images.yml`), weekly and by hand, with `contents: read` only, so every image is
      pulled without a registry login. It starts the sandbox with `scripts/sandbox.sh up` and runs
      `scripts/sandbox-smoke.py`: device login with DPoP through the sandbox Keycloak, a token bound
      to the key (`cnf.jkt`) for `basetool-ingest`, the service document, the installation, every
      read resource, a resolve per kind, one blueprint, stock and ship sync, and with
      `--conformance` every change-set fixture of `docs/exchange/examples/v1` (valid ones as dry
      runs accepted, invalid ones refused); then the same without the fixtures as the second
      member.*
- [ ] The E2E extension with ingest.

**Status:** local sandbox, the image pipeline and its smoke job built — WP 2.3 (#2099); the E2E
extension follows

### REQ-XCH-030 — Exchange writes appear live

After each committed exchange write the backend publishes live-sync frames on the topics and
sections the web pages and the app listen on (Lager, Blueprints, Hangar, and the Materialbörse when
offers changed) — `ExchangeLiveSync`, see REQ-XCH-013.

**Status:** built for the web pages — `ExchangeLiveSync`, the frames of REQ-XCH-013 — WP 3.3
(#2083), WP 4.1 (#2084), WP 4.2 (#2085), WP 4.4 (#2086)

### REQ-XCH-031 — The account check answers match, mismatch or unknown — never the handle

`POST /exchange/v1/me/account-check {handle}` compares the handle with the optional RSI handle on
the member's profile (REQ-SEC-072, stored since WP 1.4), case-insensitively, and answers `match`, `mismatch` or `unknown` (no handle
stored). It never returns or logs the stored handle and is rate-limited tightly.

The backend answers the relayed call at `POST /api/v1/exchange/me/account-check` under
`exchange.connect`, validates the handle with the profile's own pattern (`^[A-Za-z0-9_-]{3,60}$`,
`400` otherwise, without echoing it) and counts every answer in
`basetool_exchange_account_checks_total{outcome}`.

The gateway checks the body against `account-check-request.schema.json` — a value that is no RSI
handle is `400 SCHEMA_INVALID` naming only the pointer, never the value — relays it to the backend's
`POST /api/v1/exchange/me/account-check`, and passes on only an answer that matches
`account-check-response.schema.json`. The route is no write: it needs no `Idempotency-Key` and does
not count against the daily quota, but it has its own limit of ten per hour per client and member
(REQ-XCH-023).

- [x] Match, mismatch and unknown, the match case-insensitive; the stored handle is in no answer and
  neither handle in a log line. *`ExchangeAccountCheckControllerTest`.*
- [x] The gateway relays the route inside its own hourly limit; a value that is no handle is neither
  relayed, echoed nor logged. *`ExchangeControllerTest`, `ExchangeLimitFilterTest`.*
- [ ] End to end on the sandbox (WP 2.3, #2099).

**Status:** backend and gateway relay built — WP 3.4 (#2106); the sandbox run follows with WP 2.3

### REQ-XCH-032 — „Verbundene Anwendungen" shows and controls every connection

The web page „Verbundene Anwendungen" lists the member's connected clients with their capabilities,
installations (label, first and last seen) and recent activity, and lets the member disconnect one
installation or a whole client, undo, and confirm a staged mass change. Every new connection or
installation raises a notification and stays highlighted until seen. `ADMIN` manages the registry
on an admin page with a suspend switch. The page is web-only; the app links to it.

The page is `/connected-apps` (sidebar *Persönlich*, every member), over `/api/v1/connected-apps`.
An installation is always named as `‹client name› – „‹label›"`, the client-supplied label escaped
and never first, so a label cannot pose as the Basetool. Both disconnects ask first and re-swap the
`connected-apps :: apps` fragment; the page is the member's own and joins no peer sync.

The notification is the rule-engine event `EXCHANGE_INSTALLATION_CONNECTED` (seed `V251`,
`EVENT_RECIPIENT`), published when the installation upsert reports that it created the row, so two
concurrent first calls announce one installation once. It names the client by its registry display
name only: the client-supplied label arrives with a later call and could pose as the Basetool. An
installation counts as unseen while its notification is unread, `GET /api/v1/connected-apps` says so
per installation (`unseen`), and `POST /api/v1/connected-apps/seen` marks them read — a notification
change only, not audited.

- [x] List the clients with their capabilities and installations (label, first and last seen), and
  disconnect one installation or a whole client. *`ConnectedAppsPageControllerMvcTest`.*
- [x] The admin registry page. *See REQ-XCH-003.*
- [x] A new installation notifies its member once, by the client's name; the list reports it
  unseen until marked seen. *`ExchangeInstallationServiceTest`, `ExchangeInstallationControllerTest`,
  `ConnectedAppsControllerTest`.*
- [x] The page highlights an unseen installation („Neu") and then reports it seen; the highlight
  ends with the next load. *`ConnectedAppsPageControllerMvcTest`.*
- [x] Undo a client's changes since a chosen span, with the skipped entries listed.
  *`ConnectedAppsPageControllerMvcTest`, `ExchangeUndoControllerTest`.*
- [x] Confirm or discard a staged mass change. *`ExchangeMassChangeControllerTest`,
  `ConnectedAppsConfirmControllerMvcTest`.*
- [x] Recent activity: each client's last ten writes to the member's data, newest first, named by
  blueprint, material or item, or ship type, undone ones marked. *`ConnectedAppsControllerTest`,
  `ConnectedAppsPageControllerMvcTest`.*
- [ ] The end-to-end run on the sandbox (WP 2.3, #2099).

Each client in `GET /api/v1/connected-apps` carries `activity`: its last ten journal rows for the
member, newest first, each with the time, resource, action, the entry's name (read in one lookup per
catalogue) and whether it was undone.

**Status:** built — WP 4.5 (#2087); the end-to-end run on the sandbox follows with WP 2.3 (#2099)

### REQ-XCH-033 — The legacy extractor endpoints end at the go-live

Behind `app.ingest.legacy-endpoints.enabled` (default `true`), `/v1/refinery-extract` and
`/v1/blueprint-preview` answer `410 LEGACY_ENDPOINT_GONE` with a German update hint once the flag is
`false` at the go-live. While it is `true` their behaviour is unchanged.

The switch is `IRI_INGEST_LEGACY_ENDPOINTS_ENABLED` on the host. The refusal runs before the security
chain, so an outdated extractor sees the hint (*„Diese Schnittstelle wurde abgeschaltet. Bitte
aktualisiere den SC Extractor auf die neueste Version."*) whether or not its token is still
accepted. `basetool_ingest_legacy_endpoints_enabled` reports the switch and
`basetool_ingest_legacy_gone_total` counts the refusals.

**Acceptance**

- [x] With the flag off both legacy routes answer `410 LEGACY_ENDPOINT_GONE` with the German hint,
  before authentication; with it on they reach the security chain as before
  (`LegacyEndpointGoneFilterTest`).
- [ ] The flag is switched off on production at the go-live (a gated write, WP 6).

**Status:** switch built — WP 3.2 (#2082); switched off with WP 6 (#2092)

## Threat model

| Threat | Countered by |
| --- | --- |
| Stolen refresh or access token | DPoP binding of both (REQ-XCH-005/-006); tokens only in the platform secret store (REQ-XCH-027) |
| Device-code phishing (RFC 8628 §5.4) | themed device page warning, notification and highlight of every new connection, 600 s code lifespan (REQ-XCH-005/-032) — countered, not prevented |
| A revoked installation refreshing its way back | persistent `jkt` deny list (REQ-XCH-008) |
| A member who leaves keeping access | departure revocations (REQ-XCH-008) |
| Malicious client update, compromised maintainer account | capability scoping, own-data-only, journal and undo, guard, suspension; signing recommended (REQ-XCH-002/-009/-021/-022) — accepted residual risk |
| Compromised admin account (no capability ceiling) | audit area „Verbundene Anwendungen", `ExchangeRegistryChanged` alert, suspension — accepted risk (ADR-0217) |
| An admin's client running with admin authority | reduced exchange authentication and ArchUnit rule (REQ-XCH-009) |
| Confused deputy on the relay hop; forged `X-Exchange-*` headers | headers honoured only from the gateway identity; explicit relay route list (REQ-XCH-010, REQ-XCH-001) |
| Replay and cross-member idempotency replay | idempotency keyed per client and member, gates before cache (REQ-XCH-020) |
| Enumeration through resolve or account check | resolve returns catalogue data only; account check never returns the handle and is tightly limited (REQ-XCH-012/-031) |
| DoS against Redis (shared with sessions, `noeviction`) or the backend | hard byte budgets, quotas, batch cap, larger Redis (REQ-XCH-023, ADR-0221) |
| Guard evasion by batching, near-zero cuts or overwriting updates | window counting rules (REQ-XCH-021) |
| Silent removal of Materialbörse offers by a sync book-out | reported and audited, not undoable — accepted (REQ-XCH-016/-022) |
| The version gate bypassed by a manipulated client | cooperative by design — accepted (REQ-XCH-024) |
| Data poisoning of org-wide views | own personal rows only, validated through the domain services (REQ-XCH-009/-016) |
| Token leakage via backups, diagnostics or a problem-report webhook | client security requirements (REQ-XCH-027) |
| A switchable issuer used for phishing | only through a developer environment variable, never in the UI (REQ-XCH-027) |
| The installation label as a spoofing channel or PC-name leak | length and character limits, always after the client name, never logged or audited (REQ-XCH-007) |

## Out of scope

- The backend API for the web and the app ([`api-conventions.md`](api-conventions.md)).
- Recipes, mining, shops, selling and salvage data — reference data on both sides; only identifiers
  must agree.
- A client's own data that has no Basetool counterpart (VerseKit's mission log, wish list, overlay
  settings).

## Open questions

None; every decision of epic #2078 §8 is taken.
