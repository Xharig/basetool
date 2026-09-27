"""Prepares the exchange OpenAPI document for rendering without the network.

The committed document refers to its schemas by their published URLs. This writes a copy whose
references point at local copies of the schemas, each without its absolute $id, so the renderer
resolves every reference from the repository instead of fetching it.
"""

import argparse
import json
import pathlib
import sys
import tempfile

BASE = "https://ingest.profit-base.online/exchange/v1/schemas/"


def localise(node, prefix: str):
    """Rewrites every $ref under BASE to prefix, recursively.

    Args:
        node: a parsed JSON value.
        prefix: what replaces BASE.

    Returns:
        The rewritten value.
    """
    if isinstance(node, dict):
        return {
            key: (value.replace(BASE, prefix, 1)
                  if key == "$ref" and isinstance(value, str) else localise(value, prefix))
            for key, value in node.items()
        }
    if isinstance(node, list):
        return [localise(value, prefix) for value in node]
    return node


def prepare(openapi: pathlib.Path, schemas: pathlib.Path, out: pathlib.Path) -> pathlib.Path:
    """Writes the local copy of the document and its schemas.

    Args:
        openapi: the committed OpenAPI document.
        schemas: the directory of the committed schemas.
        out: the directory to write into.

    Returns:
        The path of the prepared document.
    """
    (out / "schemas").mkdir(parents=True, exist_ok=True)
    for schema in sorted(schemas.glob("*.schema.json")):
        document = json.loads(schema.read_text(encoding="utf-8"))
        document.pop("$id", None)
        (out / "schemas" / schema.name).write_text(
            json.dumps(localise(document, ""), indent=2), encoding="utf-8")
    prepared = out / "openapi.json"
    document = json.loads(openapi.read_text(encoding="utf-8"))
    prepared.write_text(json.dumps(localise(document, "schemas/"), indent=2), encoding="utf-8")
    return prepared


def selftest() -> None:
    """Proves the references are rewritten and the $id removed."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / "in").mkdir()
        (root / "in" / "a.schema.json").write_text(
            json.dumps({"$id": BASE + "a.schema.json", "properties": {"b": {"$ref": "b.schema.json"}}}),
            encoding="utf-8")
        (root / "api.json").write_text(
            json.dumps({"paths": {"/x": {"$ref": BASE + "a.schema.json#/$defs/y"}}}), encoding="utf-8")
        prepared = prepare(root / "api.json", root / "in", root / "out")
        api = json.loads(prepared.read_text(encoding="utf-8"))
        assert api["paths"]["/x"]["$ref"] == "schemas/a.schema.json#/$defs/y", api
        schema = json.loads((root / "out" / "schemas" / "a.schema.json").read_text(encoding="utf-8"))
        assert "$id" not in schema and schema["properties"]["b"]["$ref"] == "b.schema.json", schema
    print("selftest ok")


def main() -> int:
    """Prepares the document, or runs the self-test with --selftest.

    Returns:
        The exit status.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--out", default="build/exchange-reference")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    repo = pathlib.Path(__file__).resolve().parents[2]
    prepared = prepare(
        repo / "ingest/src/main/resources/api/exchange-v1.openapi.json",
        repo / "ingest/src/main/resources/exchange/v1/schemas",
        pathlib.Path(args.out))
    print(prepared)
    return 0


if __name__ == "__main__":
    sys.exit(main())
