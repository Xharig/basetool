# ADR-0228 — A login forms provider shows the device code on the consent page

- **Status:** Accepted — owner decision 2026-09-27 (option A of the second security review's M1).
- **Date:** 2026-09-27
- **Deciders:** @greluc
- **Related:** spec [`external-exchange.md`](../specs/external-exchange.md) (`REQ-XCH-005`,
  `REQ-XCH-027`) · [ADR-0217](0217-third-party-clients-are-public-device-grant-clients-in-a-db-registry.md) ·
  [ADR-0055](0055-keycloak-spi-jar-as-promotable-oci-artifact.md) ·
  [ADR-0226](0226-a-keycloak-admin-extension-ends-one-client-inside-a-shared-session.md)

## Context

An attacker can start a device login with a public client id and send a member the genuine
`verification_uri_complete` link. Keycloak's `DeviceEndpoint` then skips the page where the code is
typed, and with it the theme's RFC 8628 §5.4 warning; a member signed in through SSO sees only the
consent page (security review 2 of #2092, M1). The owner decided that the consent page carries the
same warning and shows the user code, so the member can compare it with the code on their PC, and
that clients show only the bare `verification_uri`.

Keycloak 26.7.4 gives the consent page (`login-oauth-grant.ftl`) only `OAuthGrantBean` — the session
code, the client and the requested scopes — plus the common beans. The verified code lives only in
the authentication session's client note `OAUTH2_DEVICE_VERIFIED_USER_CODE`, which no bean exposes,
so the theme alone cannot show it.

## Decision

1. **`keycloak-spi` adds a login forms provider** (`DeviceConsentLoginFormsProviderFactory`, id
   `krt-freemarker`) that extends Keycloak's `FreeMarkerLoginFormsProviderFactory`. Its provider
   renders every page as Keycloak's own does and, on the consent page, sets the template attribute
   `krtDeviceUserCode` from that client note, in the display form the client was given
   (`OAuth2DeviceUserCodeProvider.display`). Outside a device login the attribute is absent.
2. **It becomes the `login` SPI's default by its `order()` of 100, with no configuration.** Keycloak
   picks a default provider in `DefaultKeycloakSessionFactory.resolveDefaultProvider`: a configured
   `spi-login--provider` wins, otherwise the factory with the highest positive `order()`. The Quarkus
   distribution calls the same method when it augments the server (`KeycloakProcessor.checkProviders`
   in `keycloak-quarkus-server-deployment` 26.7.4), and `start` without `--optimized` augments again
   whenever the providers change. No compose file, Quadlet unit or realm setting changes, and the
   production step is the usual SPI jar update.
3. **`login-oauth-grant.ftl` shows the warning and the code** when the attribute is present, in the
   theme's warning box, with its own DE/EN messages. Without the SPI jar the page renders as before.

## Consequences

- A member who follows a code link still sees the warning and the code before „Erlauben".
- The provider extends `FreeMarkerLoginFormsProvider` in `keycloak-services` and implements the
  internal `login` SPI; Keycloak logs `KC-SERVICES0047` for it at start, as for the Discord
  providers. **Every Keycloak upgrade re-checks** that the class, its constructor, the protected
  `authenticationSession` and `attributes` fields, `createOAuthGrant`, the client note name and the
  default-provider rule are unchanged, and renders the device consent page once (the sandbox smoke
  test does it).
- Another extension that also replaces the `login` SPI would compete by `order()`; there is none.

## Alternatives considered

- **The warning without the code, theme only**, conditioned on the client's device-grant attribute.
  No Keycloak internals, but the member cannot compare codes. Rejected by the owner.
- **Selecting the provider with `KC_SPI_LOGIN__PROVIDER`.** Works too, but adds a setting to every
  stack for no gain once the order decides. Not needed.
- **Reading the code from the page URL in JavaScript.** Present only when the member was already
  signed in; a sign-in in between loses it. Rejected.
