"""Runs a device login and a DPoP-bound sync against a running local exchange sandbox (REQ-XCH-029).

It signs in `sandbox-member` through the device grant of `sandbox-client` exactly as a member would
in a browser, takes the DPoP-bound token, and then calls the service document, labels the
installation, reads every resource, resolves a blueprint, a ship type and a material, and syncs one
blueprint, one stock lot and one ship. The gateway checks every answer against its v1 schema, so a
passing run also proves the answers keep the contract. With `--conformance` it also sends every
change-set conformance fixture of `docs/exchange/examples/v1`: each valid one as a dry run, which
must be accepted, and each invalid one, which must be refused. It signs with the DPoP reference in
`docs/exchange/dpop-reference`, standard library only.
"""

import argparse
import base64
import html
import http.cookiejar
import json
import pathlib
import re
import ssl
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "docs" / "exchange" / "dpop-reference"))

from dpop_reference import (  # noqa: E402
    NonceCache,
    asks_for_nonce,
    authorization_header,
    build_proof,
    delete_installation_key,
    open_installation_key,
)

PUBLIC_KEYCLOAK = "http://host.docker.internal:18080"
REALM = "/auth/realms/iri"
SCOPES = ("offline_access exchange.connect exchange.blueprints.read exchange.blueprints.write "
          "exchange.stock.read exchange.stock.write exchange.hangar.read exchange.hangar.write "
          "exchange.demand.read")


class Smoke:
    """One smoke run: the device login, then the calls, each reported as a step."""

    def __init__(self, args: argparse.Namespace, key) -> None:
        """Keeps the settings and the installation key.

        Args:
            args: the command line.
            key: the DPoP key of this run.
        """
        self.args = args
        self.key = key
        self.local_keycloak = args.keycloak.rstrip("/")
        self.jar = http.cookiejar.CookieJar()
        smoke = self

        class Rewrite(urllib.request.HTTPRedirectHandler):
            """Follows Keycloak's redirects to the advertised host on the local port."""

            def redirect_request(self, req, fp, code, msg, headers, newurl):
                """Rewrites the redirect target to the local Keycloak and keeps its cookies.

                Keycloak marks its cookies `Secure` for a request to 127.0.0.1 even over plain
                HTTP, so the cookie jar would drop them on the redirect it answers with.
                """
                smoke.unsecure_cookies()
                return super().redirect_request(req, fp, code, msg, headers, smoke.local(newurl))

        self.browser = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(self.jar), Rewrite())
        self.tls = ssl.create_default_context(cafile=str(args.ca))
        self.nonces = NonceCache()
        self.token = None
        self.count = 0

    def local(self, url: str) -> str:
        """Maps the advertised Keycloak origin to the local one."""
        return url.replace(PUBLIC_KEYCLOAK, self.local_keycloak)

    def step(self, label: str, ok: bool, detail: str = "") -> None:
        """Reports a step and stops the run at the first failure."""
        self.count += 1
        print(f"{'PASS' if ok else 'FAIL'} {label}" + (f" — {detail}" if not ok and detail else ""))
        if not ok:
            sys.exit(1)

    def unsecure_cookies(self) -> None:
        """Clears the `Secure` flag of every cookie so plain-HTTP Keycloak requests carry them."""
        for cookie in self.jar:
            cookie.secure = False

    def page(self, url: str, data: dict | None = None) -> tuple[str, str]:
        """Loads a Keycloak page as a browser would, submitting a form when data is given."""
        body = urllib.parse.urlencode(data).encode() if data is not None else None
        self.unsecure_cookies()
        with self.browser.open(urllib.request.Request(self.local(url), data=body)) as answer:
            return answer.geturl(), answer.read().decode("utf-8", "replace")

    @staticmethod
    def form(text: str) -> tuple[str | None, dict]:
        """Reads a page's form action and its hidden and text fields."""
        match = re.search(r'<form[^>]*action="([^"]+)"', text)
        action = html.unescape(match.group(1)) if match else None
        fields = {}
        for tag in re.findall(r"<input[^>]*>", text):
            name = re.search(r'name="([^"]+)"', tag)
            value = re.search(r'value="([^"]*)"', tag)
            kind = re.search(r'type="([^"]+)"', tag)
            if name and (kind is None or kind.group(1) in ("hidden", "text")):
                fields[name.group(1)] = html.unescape(value.group(1)) if value else ""
        return action, fields

    def post_form(self, url: str, data: dict, dpop: str | None = None) -> tuple[int, dict]:
        """Posts a form to Keycloak and reads the JSON answer."""
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if dpop:
            headers["DPoP"] = dpop
        request = urllib.request.Request(
            self.local(url), data=urllib.parse.urlencode(data).encode(), headers=headers)
        try:
            with urllib.request.urlopen(request) as answer:
                return answer.status, json.load(answer)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def login(self) -> None:
        """Runs the device grant: authorise, sign in and consent, then poll for the token."""
        base = PUBLIC_KEYCLOAK + REALM + "/protocol/openid-connect"
        status, device = self.post_form(
            base + "/auth/device", {"client_id": self.args.client, "scope": SCOPES})
        self.step("device authorization answers", status == 200, str(status))
        url, text = self.page(device["verification_uri_complete"])
        for _ in range(6):
            action, fields = self.form(text)
            if "password" in text and "username" in fields:
                fields.update({"username": self.args.user, "password": self.args.password})
            elif 'name="accept"' in text:
                fields["accept"] = "yes"
            elif "user_code" not in fields:
                break
            url, text = self.page(urllib.parse.urljoin(url, action), fields)
        done = "success" in text.lower() or "erfolgreich" in text.lower()
        self.step("the member signs in and consents on the device page", done, url)
        token_url = self.local(base + "/token")
        for _ in range(10):
            status, answer = self.post_form(
                base + "/token",
                {"grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                 "device_code": device["device_code"], "client_id": self.args.client},
                build_proof(self.key, "POST", token_url))
            if status == 200:
                self.token = answer
                break
            time.sleep(2)
        self.step("the token endpoint issues a token", self.token is not None, str(status))
        claims = json.loads(base64.urlsafe_b64decode(
            self.token["access_token"].split(".")[1] + "=="))
        self.step("the token is DPoP-bound to the installation key",
                  claims.get("cnf", {}).get("jkt") == self.key.thumbprint())
        audience = claims["aud"] if isinstance(claims["aud"], list) else [claims["aud"]]
        self.step("the token names the gateway audience", "basetool-ingest" in audience)

    def call(self, method: str, path: str, body: dict | None = None,
             write: bool = False) -> tuple[int, dict]:
        """Calls the gateway with a DPoP proof, retrying once on a nonce challenge."""
        url = self.args.gateway.rstrip("/") + path
        access = self.token["access_token"]
        idempotency = str(uuid.uuid4()) if write else None
        for _ in range(2):
            headers = {
                "Authorization": authorization_header(access),
                "DPoP": build_proof(self.key, method, url, access_token=access,
                                    nonce=self.nonces.get(url)),
                "Accept": "application/json",
                "User-Agent": "BasetoolSandboxSmoke/1.0.0 (+https://krt-profit.github.io/basetool/)",
            }
            data = None
            if body is not None:
                headers["Content-Type"] = "application/json"
                data = json.dumps(body).encode()
            if idempotency:
                headers["Idempotency-Key"] = idempotency
            request = urllib.request.Request(url, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(request, context=self.tls) as answer:
                    self.nonces.update(url, dict(answer.headers))
                    return answer.status, json.load(answer)
            except urllib.error.HTTPError as error:
                self.nonces.update(url, dict(error.headers))
                raw = error.read()
                problem = json.loads(raw) if raw else {}
                if asks_for_nonce(error.code, dict(error.headers)):
                    continue
                return error.code, problem
        return 401, {}

    def run(self) -> None:
        """Signs in and exercises the exchange."""
        self.login()
        for label, method, path, body in [
            ("the service document", "GET", "/exchange/v1", None),
            ("the installation is labelled", "POST", "/exchange/v1/me/installation",
             {"label": "Sandbox smoke"}),
            ("the blueprints", "GET", "/exchange/v1/me/blueprints", None),
            ("the stock", "GET", "/exchange/v1/me/stock", None),
            ("the ships", "GET", "/exchange/v1/me/ships", None),
            ("the org demand", "GET", "/exchange/v1/me/org-demand", None),
            ("the locations", "GET", "/exchange/v1/catalog/locations", None),
        ]:
            status, answer = self.call(method, path, body)
            self.step(label, status in (200, 201), f"{status} {json.dumps(answer)[:300]}")
            if path == "/exchange/v1/me/stock":
                stock = answer
        refs = {}
        for kind, ref in [("BLUEPRINT", {"scRecord": "BP_CRAFT_SBXM_HELMET_01"}),
                          ("SHIP_TYPE", {"name": "Sandbox Miner"}),
                          ("MATERIAL", {"name": "Sandbox Metal"})]:
            status, answer = self.call("POST", "/exchange/v1/catalog/resolve",
                                       {"kind": kind, "refs": [ref]})
            resolved = status == 200 and answer["results"][0].get("status") == "resolved"
            self.step(f"a {kind.lower()} resolves", resolved, json.dumps(answer)[:300])
            refs[kind] = answer["results"][0]["ref"]
        held = next((lot["quantity"]["amount"] for lot in stock.get("items", [])
                     if lot["material"]["bt"] == refs["MATERIAL"]["bt"]
                     and lot["location"]["name"] == "Sandbox Station Storage"
                     and lot["quality"] == 800 and not lot["stolen"]), 0)
        for label, path, body in [
            ("a blueprint syncs", "/exchange/v1/me/blueprints/changes",
             {"ops": [{"op": "add", "ref": refs["BLUEPRINT"],
                       "provenance": {"source": "log"}}]}),
            ("a stock lot syncs", "/exchange/v1/me/stock/changes",
             {"ops": [{"op": "set-quantity", "material": refs["MATERIAL"],
                       "location": {"name": "Sandbox Station Storage"}, "quality": 800,
                       "stolen": False, "quantity": {"amount": held + 2.5, "unit": "SCU"},
                       "expectedQuantity": {"amount": held, "unit": "SCU"}}]}),
            ("a ship syncs", "/exchange/v1/me/ships/changes",
             {"ops": [{"op": "upsert", "externalId": f"smoke-{uuid.uuid4().hex[:8]}",
                       "shipType": refs["SHIP_TYPE"], "name": "Smoke Miner",
                       "insurance": {"kind": "LTI"}}]}),
        ]:
            status, answer = self.call("POST", path, body, write=True)
            taken = answer.get("applied", 0) + answer.get("unchanged", 0)
            applied = status == 200 and taken >= 1 and answer.get("notApplied", 0) == 0
            self.step(label, applied, f"{status} {json.dumps(answer)[:300]}")
        if self.args.conformance:
            self.conformance()
        print(f"all {self.count} steps passed")

    def conformance(self) -> None:
        """Sends the change-set fixtures: valid ones as dry runs, invalid ones to be refused."""
        examples = ROOT / "docs" / "exchange" / "examples" / "v1"
        routes = {"blueprintChangeSet": "blueprints", "stockChangeSet": "stock",
                  "shipChangeSet": "ships"}
        for folder in sorted(examples.glob("change-set--*")):
            resource = routes[folder.name.split("--", 1)[1]]
            path = f"/exchange/v1/me/{resource}/changes"
            for fixture in sorted((folder / "valid").glob("*.json")):
                body = json.loads(fixture.read_text(encoding="utf-8"))
                body["dryRun"] = True
                status, answer = self.call("POST", path, body, write=True)
                self.step(f"fixture {folder.name}/valid/{fixture.name} is accepted",
                          status == 200, f"{status} {json.dumps(answer)[:300]}")
            for fixture in sorted((folder / "invalid").glob("*.json")):
                body = json.loads(fixture.read_text(encoding="utf-8"))
                status, answer = self.call("POST", path, body, write=True)
                self.step(f"fixture {folder.name}/invalid/{fixture.name} is refused",
                          status in (400, 413), f"{status} {json.dumps(answer)[:300]}")


def main() -> int:
    """Parses the command line and runs the smoke test with a throwaway key.

    Returns:
        0 when every step passed.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--gateway", default="https://127.0.0.1:11262")
    parser.add_argument("--keycloak", default="http://127.0.0.1:18080")
    parser.add_argument("--ca", type=pathlib.Path,
                        default=ROOT / "docker" / "test-tls" / "basetool-test-ca.crt")
    parser.add_argument("--client", default="sandbox-client")
    parser.add_argument("--user", default="sandbox-member")
    parser.add_argument("--password", default="sandbox-member-pw-do-not-use-in-prod")
    parser.add_argument("--conformance", action="store_true",
                        help="also send the change-set conformance fixtures")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        key_file = pathlib.Path(tmp) / "smoke-dpop.pem"
        name = f"basetool-sandbox-smoke-{uuid.uuid4().hex[:8]}"
        key = open_installation_key(name, key_file=key_file)
        try:
            Smoke(args, key).run()
        finally:
            delete_installation_key(name, key_file=key_file)
    return 0


if __name__ == "__main__":
    sys.exit(main())
