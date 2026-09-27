#!/usr/bin/env python3
#
# Profit Basetool - squadron-management web app.
# Copyright (C) 2026 Lucas Greuloch
#
# SPDX-License-Identifier: GPL-3.0-only
"""Build the Keycloak realm of the local exchange sandbox (REQ-XCH-029).

The realm is the E2E realm with its users replaced by the sandbox accounts, plus what the
production provisioner (``scripts/provision-keycloak-realm.py``) defines: the token and session
settings, the Basetool client scopes, the ingest gateway's service account and the approved
third-party client template, instantiated as ``sandbox-client``. Every secret and password in it
is a throwaway committed on purpose.

Usage::

    python3 scripts/build-sandbox-realm.py             # write docker/sandbox/keycloak/realm-iri.json
    python3 scripts/build-sandbox-realm.py --check     # fail when the committed file is stale
    python3 scripts/build-sandbox-realm.py --selftest  # prove the invariants the realm must keep
"""

from __future__ import annotations

import argparse
import copy
import importlib.util
import json
import re
import sys
from pathlib import Path
from types import ModuleType

REPO = Path(__file__).resolve().parent.parent
PROVISIONER = REPO / "scripts" / "provision-keycloak-realm.py"
E2E_REALM = REPO / "frontend" / "src" / "e2e" / "resources" / "realm-export.e2e.json"
BUILTIN_SCOPES = REPO / "scripts" / "keycloak" / "builtin-client-scopes.json"
OUTPUT = REPO / "docker" / "sandbox" / "keycloak" / "realm-iri.json"

REALM = "iri"
DISPLAY_NAME = "IRI (SANDBOX REALM - throwaway, do not use in prod)"
THROWAWAY = "do-not-use-in-prod"

SANDBOX_CLIENT = {
    "clientId": "sandbox-client",
    "name": "Sandbox Client",
    "description": "The third-party test client of the local exchange sandbox (REQ-XCH-029)",
}

SUSPENDED_CLIENT = {
    "clientId": "sandbox-suspended-client",
    "name": "Suspended Example Client",
    "description": "A sandbox client the registry lists as suspended, to show the refusal",
}

GATEWAY_CLIENT = "basetool-ingest-gateway"

CLIENT_SECRETS = {
    "basetool-frontend": f"sandbox-frontend-client-secret-{THROWAWAY}",
    "backend-service": f"sandbox-backend-service-secret-{THROWAWAY}",
    GATEWAY_CLIENT: f"sandbox-ingest-gateway-secret-{THROWAWAY}",
}

SERVICE_ACCOUNT_IDS = {
    "backend-service": "5a4d0000-0000-4000-8000-0000000000a1",
    GATEWAY_CLIENT: "5a4d0000-0000-4000-8000-0000000000a2",
}

USERS = [
    {
        "id": "5a4d0000-0000-4000-8000-000000000001",
        "username": "sandbox-member",
        "roles": ["KRT Member"],
    },
    {
        "id": "5a4d0000-0000-4000-8000-000000000002",
        "username": "sandbox-member-2",
        "roles": ["KRT Member"],
    },
    {
        "id": "5a4d0000-0000-4000-8000-000000000003",
        "username": "sandbox-admin",
        "roles": ["Admin", "Officer", "KRT Member"],
    },
]

LOCALES = {"internationalizationEnabled": True, "supportedLocales": ["de", "en"],
           "defaultLocale": "en"}

PRODUCTION_MARKERS = ("profit-base.online", "krt-profit.de")


def load_provisioner() -> ModuleType:
    """Import the production provisioner, whose hyphenated file name is not importable."""
    name = "provision_keycloak_realm"
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, PROVISIONER)
    if spec is None or spec.loader is None:
        raise SystemExit(f"FATAL: cannot load the provisioner from {PROVISIONER}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


def scope_representation(scope) -> dict:
    """Convert a provisioner ``ScopeSpec`` to the realm-import representation of a client scope."""
    return {
        "name": scope.name,
        "protocol": "openid-connect",
        "attributes": dict(scope.attributes),
        "protocolMappers": [mapper_representation(mapper) for mapper in scope.mappers],
    }


def mapper_representation(mapper: dict) -> dict:
    """Complete a provisioner mapper with the fields the realm import expects."""
    return {
        "name": mapper["name"],
        "protocol": "openid-connect",
        "protocolMapper": mapper["protocolMapper"],
        "consentRequired": False,
        "config": dict(mapper["config"]),
    }


def client_representation(spec, secret: str | None) -> dict:
    """Convert a provisioner ``ClientSpec`` to the realm-import representation of a client.

    :param spec: the client's production shape
    :param secret: the throwaway secret of a confidential client, ``None`` for a public one
    :return: the client as Keycloak imports it
    """
    client = {"clientId": spec.client_id, **spec.create_only, **spec.fields}
    client.update({
        "attributes": dict(spec.attributes),
        "redirectUris": list(spec.redirect_uris),
        "webOrigins": list(spec.web_origins),
        "defaultClientScopes": list(spec.default_scopes),
        "optionalClientScopes": list(spec.optional_scopes),
    })
    if spec.mappers:
        client["protocolMappers"] = [mapper_representation(m) for m in spec.mappers]
    if secret is not None:
        client["secret"] = secret
    return client


def spec_by_id(provisioner: ModuleType, client_id: str):
    """Return the provisioner's production shape of one first-party client."""
    specs = provisioner.client_specs(REALM, "http://localhost:18081", None)
    return next(spec for spec in specs if spec.client_id == client_id)


def user_representation(user: dict) -> dict:
    """Build one synthetic member account with its fixed id and throwaway password."""
    name = user["username"]
    return {
        "id": user["id"],
        "username": name,
        "enabled": True,
        "emailVerified": True,
        "email": f"{name}@example.invalid",
        "credentials": [{"type": "password", "value": f"{name}-pw-{THROWAWAY}",
                         "temporary": False}],
        "realmRoles": [f"default-roles-{REALM}", *user["roles"]],
    }


def service_account_user(client_id: str, client_roles: dict[str, list[str]]) -> dict:
    """Build the service-account user of a confidential client."""
    user = {
        "id": SERVICE_ACCOUNT_IDS[client_id],
        "username": f"service-account-{client_id}",
        "enabled": True,
        "serviceAccountClientId": client_id,
        "realmRoles": [f"default-roles-{REALM}"],
    }
    if client_roles:
        user["clientRoles"] = {name: sorted(roles) for name, roles in client_roles.items()}
    return user


def build_realm(provisioner: ModuleType, base: dict, builtin_scopes: list[dict]) -> dict:
    """Assemble the sandbox realm from the E2E realm, the built-in scopes and the provisioner.

    :param provisioner: the loaded production provisioner module
    :param base: the E2E realm export
    :param builtin_scopes: Keycloak's built-in OpenID Connect client scopes
    :return: the realm representation, ready to serialise
    """
    realm = copy.deepcopy(base)
    realm.pop("users", None)
    realm["displayName"] = DISPLAY_NAME
    realm.update(provisioner.REALM_SETTINGS)
    realm.update(LOCALES)

    builtin_names = {scope["name"] for scope in builtin_scopes}
    own = [scope_representation(scope) for scope in provisioner.SCOPES]
    clash = builtin_names & {scope["name"] for scope in own}
    if clash:
        raise SystemExit(f"FATAL: provisioner scopes shadow built-in scopes: {sorted(clash)}")
    realm["clientScopes"] = copy.deepcopy(builtin_scopes) + own
    realm["defaultDefaultClientScopes"] = list(provisioner._STANDARD_DEFAULT)
    realm["defaultOptionalClientScopes"] = list(provisioner._STANDARD_OPTIONAL)
    realm["scopeMappings"] = [{"clientScope": "offline_access", "roles": ["offline_access"]}]

    clients = []
    for client in base["clients"]:
        client = copy.deepcopy(client)
        client["secret"] = CLIENT_SECRETS[client["clientId"]]
        client["name"] = client["name"].replace("(E2E)", "(Sandbox)")
        if client["clientId"] == "backend-service":
            spec = spec_by_id(provisioner, "backend-service")
            client["defaultClientScopes"] = list(spec.default_scopes)
            client["optionalClientScopes"] = list(spec.optional_scopes)
        clients.append(client)
    gateway = spec_by_id(provisioner, GATEWAY_CLIENT)
    clients.append(client_representation(gateway, CLIENT_SECRETS[GATEWAY_CLIENT]))
    for entry in (SANDBOX_CLIENT, SUSPENDED_CLIENT):
        clients.append(client_representation(provisioner.external_client_spec(entry), None))
    realm["clients"] = clients

    backend_roles = spec_by_id(provisioner, "backend-service").service_account_roles or {}
    realm["users"] = [user_representation(user) for user in USERS] + [
        service_account_user("backend-service",
                             {k: v for k, v in backend_roles.items() if k != "<realm>"}),
        service_account_user(GATEWAY_CLIENT, {}),
    ]
    return realm


def render(realm: dict) -> str:
    """Serialise the realm deterministically."""
    return json.dumps(realm, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def generate() -> str:
    """Build the realm from the files in this checkout and return its serialised form."""
    provisioner = load_provisioner()
    base = json.loads(E2E_REALM.read_text(encoding="utf-8"))
    builtin = json.loads(BUILTIN_SCOPES.read_text(encoding="utf-8"))
    return render(build_realm(provisioner, base, builtin))


def problems(realm: dict, provisioner: ModuleType) -> list[str]:
    """List every way the realm breaks the sandbox's invariants.

    :param realm: the realm representation
    :param provisioner: the loaded production provisioner module
    :return: one line per broken invariant, empty when the realm is sound
    """
    found: list[str] = []
    text = render(realm)
    for marker in PRODUCTION_MARKERS:
        if marker in text:
            found.append(f"the realm names the production host {marker}")
    clients = {c["clientId"]: c for c in realm.get("clients", [])}
    scopes = {s["name"] for s in realm.get("clientScopes", [])}
    for client_id, client in clients.items():
        secret = client.get("secret")
        if not client.get("publicClient") and (not secret or THROWAWAY not in secret):
            found.append(f"client {client_id} has no throwaway secret")
        for key in ("defaultClientScopes", "optionalClientScopes"):
            for name in client.get(key, []):
                if name not in scopes:
                    found.append(f"client {client_id} names the undefined scope {name}")
    sandbox = clients.get(SANDBOX_CLIENT["clientId"])
    if sandbox is None:
        found.append("sandbox-client is missing")
    else:
        attributes = sandbox.get("attributes", {})
        if not (sandbox.get("publicClient") and sandbox.get("consentRequired")):
            found.append("sandbox-client must be public and require consent")
        if sandbox.get("redirectUris") or sandbox.get("standardFlowEnabled"):
            found.append("sandbox-client must have no redirect URI and no code flow")
        if attributes.get("dpop.bound.access.tokens") != "true":
            found.append("sandbox-client tokens must be DPoP-bound")
        if attributes.get("oauth2.device.authorization.grant.enabled") != "true":
            found.append("sandbox-client must use the device grant")
        missing = set(provisioner.EXCHANGE_SCOPE_NAMES) - set(sandbox.get("optionalClientScopes", []))
        if missing:
            found.append(f"sandbox-client lacks exchange scopes {sorted(missing)}")
    for scope in provisioner.SCOPES:
        if scope.name not in scopes:
            found.append(f"the provisioner scope {scope.name} is missing")
    for key, value in provisioner.REALM_SETTINGS.items():
        if realm.get(key) != value:
            found.append(f"realm setting {key} differs from the provisioner")
    usernames = set()
    for user in realm.get("users", []):
        usernames.add(user["username"])
        if not re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                            user.get("id", "")):
            found.append(f"user {user['username']} has no fixed id")
        for credential in user.get("credentials", []):
            if not credential.get("value", "").endswith(THROWAWAY):
                found.append(f"user {user['username']} has a password that is not a throwaway")
        if f"default-roles-{REALM}" not in user.get("realmRoles", []):
            found.append(f"user {user['username']} lacks the default roles, so no offline token")
    for user in USERS:
        if user["username"] not in usernames:
            found.append(f"user {user['username']} is missing")
    return found


def selftest() -> int:
    """Prove the checks catch a broken realm and the build is deterministic.

    :return: the process exit code
    """
    failures: list[str] = []

    def expect(label: str, condition: bool) -> None:
        print(f"  {'ok  ' if condition else 'FAIL'} {label}")
        if not condition:
            failures.append(label)

    provisioner = load_provisioner()
    base = json.loads(E2E_REALM.read_text(encoding="utf-8"))
    builtin = json.loads(BUILTIN_SCOPES.read_text(encoding="utf-8"))
    realm = build_realm(provisioner, base, builtin)
    expect("the generated realm is sound", problems(realm, provisioner) == [])
    expect("two builds are byte-identical",
           render(realm) == render(build_realm(provisioner, base, builtin)))
    expect("the E2E users are dropped",
           not any(u["username"].startswith("test-") for u in realm["users"]))

    broken = copy.deepcopy(realm)
    sandbox = next(c for c in broken["clients"] if c["clientId"] == "sandbox-client")
    sandbox["attributes"]["dpop.bound.access.tokens"] = "false"
    expect("a sandbox-client without DPoP is caught", problems(broken, provisioner) != [])

    broken = copy.deepcopy(realm)
    broken["clients"][0]["secret"] = "a-real-looking-secret"
    expect("a secret that is not a throwaway is caught", problems(broken, provisioner) != [])

    broken = copy.deepcopy(realm)
    broken["users"][0]["credentials"][0]["value"] = "hunter2"
    expect("a password that is not a throwaway is caught", problems(broken, provisioner) != [])

    broken = copy.deepcopy(realm)
    broken["clientScopes"] = [s for s in broken["clientScopes"] if s["name"] != "exchange.connect"]
    expect("a missing exchange scope is caught", problems(broken, provisioner) != [])

    broken = copy.deepcopy(realm)
    broken["displayName"] = "https://profit-base.online"
    expect("a production host name is caught", problems(broken, provisioner) != [])

    broken = copy.deepcopy(realm)
    broken["users"][0]["realmRoles"] = ["KRT Member"]
    expect("an account without the default roles is caught", problems(broken, provisioner) != [])

    broken = copy.deepcopy(realm)
    broken["users"] = broken["users"][1:]
    expect("a missing sandbox account is caught", problems(broken, provisioner) != [])

    print("selftest: FAILED" if failures else "selftest: passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    """Write, check or self-test the sandbox realm.

    :param argv: the command-line arguments, ``sys.argv[1:]`` when ``None``
    :return: the process exit code
    """
    parser = argparse.ArgumentParser(prog="build-sandbox-realm.py")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true",
                      help="fail when the committed realm differs from a fresh build")
    mode.add_argument("--selftest", action="store_true", help="prove the realm's invariants")
    args = parser.parse_args(argv)
    if args.selftest:
        return selftest()
    text = generate()
    found = problems(json.loads(text), load_provisioner())
    if found:
        for line in found:
            print(f"FAIL {line}")
        return 1
    relative = OUTPUT.relative_to(REPO).as_posix()
    if args.check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.exists() else ""
        if current != text:
            print(f"FAIL {relative} is stale; run python3 scripts/build-sandbox-realm.py "
                  f"and commit the result")
            return 1
        print(f"OK: {relative} matches a fresh build")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    print(f"wrote {relative}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
