# Local sandbox

The sandbox is the Profit Basetool on your own machine: the Exchange API gateway, the backend, the
web frontend and a Keycloak realm with a test client, synthetic members and seeded data. Build and
test your client against it before you apply (REQ-XCH-029). It needs no account of ours, never talks
to production, and everything in it is thrown away when you stop it.

**Nothing in the sandbox is a real credential.** Every password, client secret and key in it is a
throwaway committed to the public repository on purpose, named `…-do-not-use-in-prod` where it is
ours to name. The TLS certificates are the committed test material whose signing key was destroyed
when they were made ([ADR-0139][adr-0139]). Never use any of these values anywhere else, and never
put a real credential into the sandbox.

## What you need

- A checkout of [krt-profit/basetool][repo]: the compose files, the seed and the start scripts live
  there.
- Docker Desktop on Windows, or Docker Engine with Compose 2.24.4 or later on Linux, and about 4 GB
  of free memory.
- The name `host.docker.internal` resolving to `127.0.0.1` on your machine — see
  [the issuer's host name](#the-issuers-host-name).

## Start, reset, stop

| | Linux | Windows (PowerShell) |
| --- | --- | --- |
| Start and seed | `scripts/sandbox.sh up` | `./scripts/sandbox.ps1 up` |
| Start from scratch | `scripts/sandbox.sh reset` | `./scripts/sandbox.ps1 reset` |
| Stop and delete all data | `scripts/sandbox.sh down` | `./scripts/sandbox.ps1 down` |

Run them from the repository root. `up` pulls the public sandbox images, starts the stack, waits
until every service is healthy and applies the seed. `reset` is `down` followed by `up`. `down`
removes the containers **and the volumes**, so the next start is a clean sandbox.

The images are `ghcr.io/krt-profit/basetool-sandbox-{backend,frontend,ingest,keycloak}`, tag
`edge` unless `BASETOOL_SANDBOX_VERSION` names another. Use a tag that matches your checkout. They
carry only test values and refuse to start as production: the application images stop under the
`prod` profile, and the Keycloak image runs only as `start-dev` — any other command, such as
`start`, ends at once with exit code 64.

To build the images from your checkout instead, add `--build` (Linux) or `-Build` (Windows). The
first build takes several minutes.

The scripts run this command line, which you can also use directly:

```bash
docker compose --env-file docker/sandbox/sandbox.env \
  -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml \
  --profile sandbox up -d --wait redis-dev db-backend-dev db-keycloak-dev keycloak-dev \
  backend-dev frontend-dev ingest-dev
docker compose --env-file docker/sandbox/sandbox.env \
  -f docker-compose.yml -f docker-compose.test.yml -f docker-compose.sandbox.yml \
  --profile sandbox run --rm sandbox-seed
```

For local images, add `-f docker-compose.sandbox-build.yml` after the sandbox file and run `build`
first. Stop with the same files and `down --volumes`. Always pass the sandbox's `--env-file`:
without it Compose would read a `.env` in the checkout instead.

The sandbox listens on `127.0.0.1` only and cannot run beside another Basetool stack on the same
machine, because both use the same ports.

## The issuer's host name

The sandbox issuer is `http://host.docker.internal:18080/auth/realms/iri`. The containers reach
Keycloak under that name inside Docker; your client and your browser must reach it under the same
name, on the loopback.

- **Linux.** The name does not exist on a Linux host. Add this line to `/etc/hosts`:

  ```text
  127.0.0.1 host.docker.internal
  ```

- **Windows.** Docker Desktop maps the name to your network address, where the sandbox does not
  listen. Add the same line to `C:\Windows\System32\drivers\etc\hosts` as an administrator, above
  the block Docker Desktop maintains, or turn off *Add the \*.docker.internal names to the host's
  etc/hosts file* in Docker Desktop's settings and add the line. Check the result with
  `Resolve-DnsName host.docker.internal`.

Both start scripts check the name and warn when it does not resolve to the loopback.

## Addresses

| | |
| --- | --- |
| Issuer | `http://host.docker.internal:18080/auth/realms/iri` |
| Device authorization | `http://host.docker.internal:18080/auth/realms/iri/protocol/openid-connect/auth/device` |
| Token | `http://host.docker.internal:18080/auth/realms/iri/protocol/openid-connect/token` |
| Gateway | `https://localhost:11262/exchange/v1` |
| Web frontend | `https://localhost:18081` |
| Keycloak admin console | `http://host.docker.internal:18080/auth/admin/` — `admin` / `sandbox-keycloak-admin-pw-do-not-use-in-prod` |

The gateway and the web frontend serve HTTPS with the committed test certificate. Trust
[`docker/test-tls/basetool-test-ca.crt`][test-ca] **in sandbox mode only**; its names include
`localhost`, `127.0.0.1` and `host.docker.internal`.

The DPoP proof's `htu` is the URL you actually call, so use the addresses exactly as above. The
sign-in itself works as in production: [authentication](authentication.md).

## Switching your client to the sandbox

Pin the production issuer. Select the sandbox only through a developer environment variable of your
client, never in its user interface ([client security](client-security.md)). One variable should
switch everything at once: the issuer, the gateway's base URL and the extra trust anchor — for
example `BASETOOL_EXCHANGE_SANDBOX=1`. A release build may ignore it altogether.

## Accounts

Sign in with these on the device page. They exist only in the sandbox realm; none of them uses
Discord.

| Username | Password | Id (`sub`) | What it is |
| --- | --- | --- | --- |
| `sandbox-member` | `sandbox-member-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-000000000001` | a member of two squadrons, IRIDIUM and Sandbox Squadron, with data |
| `sandbox-member-2` | `sandbox-member-2-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-000000000002` | a member of IRIDIUM only, for isolation checks |
| `sandbox-admin` | `sandbox-admin-pw-do-not-use-in-prod` | `5a4d0000-0000-4000-8000-000000000003` | an administrator, for the registry page in the web frontend |

All three are approved and have accepted the Terms of Use version of your checkout, which is why
the image tag should match it; `sandbox-member` has the RSI handle `Sandbox_Member` stored for the
account check.

## Clients

`sandbox-client` is a third-party client exactly as the production provisioner creates an approved
one: public, device grant only, consent required, DPoP-bound tokens, no redirect URI, every
`exchange.*` scope and `offline_access` optional. The registry lists it `ACTIVE` with every
capability.

`sandbox-suspended-client` has the same shape, but the registry lists it `SUSPENDED`: every call
with its token is refused with `403 CLIENT_SUSPENDED`.

Sign in with `sandbox-admin` on the web frontend and open `/admin/exchange-clients` to suspend a
client, change its capabilities or set a minimum version; the gateway sees a change within seconds.

## Seeded data

- **Catalogue.** A manufacturer; Sandbox City (UEX city id `990001`) and Sandbox Station (UEX space
  station id `990002`) with one warehouse location each; the ship types Sandbox Hauler and Sandbox
  Miner; the materials Sandbox Ore (raw, refines into Sandbox Metal), Sandbox Metal (refined),
  Sandbox Trade Goods (a commodity) and Sandbox Component (counted in pieces); the items Sandbox
  Rifle and Sandbox Helmet; blueprints with the scmdb tags `BP_CRAFT_SBXM_RIFLE_01`,
  `BP_CRAFT_SBXM_HELMET_01` and `BP_CRAFT_SBXM_KNIFE_01`.
- **Blueprints.** `sandbox-member` owns Sandbox Rifle and Sandbox Knife, `sandbox-member-2` owns
  Sandbox Helmet. The Basetool's default blueprints are granted to every member within a minute of
  the start.
- **Personal stock of `sandbox-member`.** Sandbox Metal in the IRIDIUM pool and in the Sandbox
  Squadron pool at the same place and quality, so the two rows form one lot; Sandbox Trade Goods,
  Sandbox Component and Sandbox Ore in no pool; one Sandbox Rifle. Part of the IRIDIUM row is
  offered on the Material Exchange.
- **Ships.** Two for `sandbox-member`, one for `sandbox-member-2`.
- **Open orders.** A material order and an item order for IRIDIUM and a material order for Sandbox
  Squadron, with minimum qualities, so `GET /exchange/v1/me/org-demand` answers.
- **The exchange switch is on.** The seed sets it in the database; the backend copies it to the
  gateway's registry mirror within 60 s and the gateway reads the mirror every 30 s, so for up to
  about 90 s after `up` every call answers `503 EXCHANGE_DISABLED` with `Retry-After`. Honour it, as
  a client must in production too.

## Checking the sandbox

[`scripts/sandbox-smoke.py`][smoke] signs in as `sandbox-member` through the device flow with a DPoP
key — opening the bare `verification_uri`, typing the code and checking that the code-entry and
consent pages carry the phishing warning and the consent page the code — reads every resource,
resolves one entry per kind and syncs one blueprint, stock lot and ship. With `--conformance` it
also sends every change-set fixture of the [conformance examples](examples/README.md): valid ones as
dry runs, which must be accepted, and invalid ones, which must be refused. It needs only Python 3
and runs again on the same data; `--user` and `--password` pick another account. CI runs it against
the published images after every release.

```sh
python3 scripts/sandbox-smoke.py --conformance
```

## Maintaining the sandbox

The realm, [`docker/sandbox/keycloak/realm-iri.json`][realm], is generated by
[`scripts/build-sandbox-realm.py`][generator] from the test realm base
[`scripts/keycloak/test-realm-base.json`][base], the production provisioner's clients, scopes and
realm settings, and Keycloak's built-in client scopes in
[`scripts/keycloak/builtin-client-scopes.json`][builtin]. The same run writes the realm of our own
E2E stack, which uses the same Keycloak image. Regenerate both after changing any of these with
`python3 scripts/build-sandbox-realm.py`; CI fails on a stale file (`--check`). The built-in
scopes are read from a realm that the pinned Keycloak version created on its own; renew them when
Keycloak is upgraded. The seed is [`docker/sandbox/seed.sql`][seed], safe to run twice.

[adr-0139]: https://github.com/krt-profit/basetool/blob/main/docs/adr/0139-shared-committed-tls-material-for-the-test-stack.md
[repo]: https://github.com/krt-profit/basetool
[test-ca]: https://github.com/krt-profit/basetool/blob/main/docker/test-tls/basetool-test-ca.crt
[realm]: https://github.com/krt-profit/basetool/blob/main/docker/sandbox/keycloak/realm-iri.json
[generator]: https://github.com/krt-profit/basetool/blob/main/scripts/build-sandbox-realm.py
[base]: https://github.com/krt-profit/basetool/blob/main/scripts/keycloak/test-realm-base.json
[builtin]: https://github.com/krt-profit/basetool/blob/main/scripts/keycloak/builtin-client-scopes.json
[seed]: https://github.com/krt-profit/basetool/blob/main/docker/sandbox/seed.sql
[smoke]: https://github.com/krt-profit/basetool/blob/main/scripts/sandbox-smoke.py
