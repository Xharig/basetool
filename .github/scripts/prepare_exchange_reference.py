"""Prepares the exchange OpenAPI reference page for rendering without the network.

The committed document refers to its schemas by their published URLs. This writes a copy whose
references point at local copies of the schemas, each without its absolute $id, so the page
resolves every reference from the site instead of fetching it. Beside them it writes the page
itself, which renders the copy with the Redoc bundle placed next to it.

With --schema-index it instead writes the index page of the site's schema directory, a table of
every schema with its title and description.

A conditional branch that narrows a `number` property to `integer` is rendered as `multipleOf: 1`,
because Redoc merges the branch into the base schema and cannot merge two different types. The
committed schemas stay as they are.
"""

import argparse
import html
import json
import pathlib
import sys
import tempfile

BASE = "https://ingest.profit-base.online/exchange/v1/schemas/"
TITLE = "Profit Basetool Exchange API reference"
BUNDLE = "redoc.standalone.js"
PAGE = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<style>body {{ margin: 0; padding: 0; }}</style>
</head>
<body>
<redoc spec-url="openapi.json"></redoc>
<script src="{bundle}"></script>
</body>
</html>
"""


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


def soften(node):
    """Renders an `integer` narrowing of a `number` property in `then`/`else` as `multipleOf: 1`.

    Args:
        node: a parsed JSON value.

    Returns:
        The value with every such narrowing rewritten, recursively.
    """
    if isinstance(node, list):
        return [soften(value) for value in node]
    if not isinstance(node, dict):
        return node
    result = {key: soften(value) for key, value in node.items()}
    base = result.get("properties", {})
    for branch in ("then", "else"):
        properties = result.get(branch, {}).get("properties", {})
        for name, narrowed in properties.items():
            if (isinstance(narrowed, dict) and narrowed.get("type") == "integer"
                    and base.get(name, {}).get("type") == "number"):
                narrowed.pop("type")
                narrowed["multipleOf"] = 1
    return result


def prepare(openapi: pathlib.Path, schemas: pathlib.Path, out: pathlib.Path) -> pathlib.Path:
    """Writes the local copy of the document, its schemas and the page.

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
            json.dumps(soften(localise(document, "")), indent=2), encoding="utf-8")
    prepared = out / "openapi.json"
    document = json.loads(openapi.read_text(encoding="utf-8"))
    prepared.write_text(json.dumps(soften(localise(document, "schemas/")), indent=2), encoding="utf-8")
    (out / "index.html").write_text(
        PAGE.format(title=html.escape(TITLE), bundle=BUNDLE), encoding="utf-8")
    return prepared


def cell(text: str) -> str:
    """Returns text safe for one Markdown table cell.

    Args:
        text: the raw text.

    Returns:
        The text on one line, with pipes escaped.
    """
    return " ".join(text.split()).replace("|", "\\|")


def schema_index(schemas: pathlib.Path) -> pathlib.Path:
    """Writes index.md into a directory of schemas, listing each with its title and description.

    Args:
        schemas: the directory holding the *.schema.json files.

    Returns:
        The path of the written page.
    """
    rows = []
    for schema in sorted(schemas.glob("*.schema.json")):
        document = json.loads(schema.read_text(encoding="utf-8"))
        rows.append(f"| [{schema.name}]({schema.name}) | {cell(document.get('title', ''))} "
                    f"| {cell(document.get('description', ''))} |")
    page = schemas / "index.md"
    page.write_text("\n".join([
        "# Schemas",
        "",
        "Every JSON Schema 2020-12 document of the Exchange API v1. Each is served at its `$id`,",
        f"`{BASE}<name>.schema.json`; the files here are copies.",
        "",
        "| Schema | Title | Description |",
        "| --- | --- | --- |",
        *rows,
        "",
    ]), encoding="utf-8")
    return page


def selftest() -> None:
    """Proves references are rewritten, $id removed, narrowings softened and the pages written."""
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        (root / "in").mkdir()
        (root / "in" / "a.schema.json").write_text(
            json.dumps({"$id": BASE + "a.schema.json", "properties": {"b": {"$ref": "b.schema.json"}}}),
            encoding="utf-8")
        (root / "in" / "q.schema.json").write_text(
            json.dumps({"properties": {"n": {"type": "number"}, "s": {"type": "string"}},
                        "if": {"properties": {"s": {"const": "x"}}},
                        "then": {"properties": {"n": {"type": "integer"}, "s": {"type": "integer"}}}}),
            encoding="utf-8")
        (root / "api.json").write_text(
            json.dumps({"paths": {"/x": {"$ref": BASE + "a.schema.json#/$defs/y"}}}), encoding="utf-8")
        prepared = prepare(root / "api.json", root / "in", root / "out")
        api = json.loads(prepared.read_text(encoding="utf-8"))
        assert api["paths"]["/x"]["$ref"] == "schemas/a.schema.json#/$defs/y", api
        schema = json.loads((root / "out" / "schemas" / "a.schema.json").read_text(encoding="utf-8"))
        assert "$id" not in schema and schema["properties"]["b"]["$ref"] == "b.schema.json", schema
        conditional = json.loads((root / "out" / "schemas" / "q.schema.json").read_text(encoding="utf-8"))
        assert conditional["then"]["properties"]["n"] == {"multipleOf": 1}, conditional
        assert conditional["then"]["properties"]["s"] == {"type": "integer"}, conditional
        page = (root / "out" / "index.html").read_text(encoding="utf-8")
        assert 'spec-url="openapi.json"' in page and f'src="{BUNDLE}"' in page, page
        (root / "in" / "a.schema.json").write_text(
            json.dumps({"title": "A", "description": "One | two\nthree"}), encoding="utf-8")
        index = schema_index(root / "in").read_text(encoding="utf-8")
        assert "| [a.schema.json](a.schema.json) | A | One \\| two three |" in index, index
        assert "| [q.schema.json](q.schema.json) |  |  |" in index, index
    print("selftest ok")


def main() -> int:
    """Prepares the document, or runs the self-test with --selftest.

    Returns:
        The exit status.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--out", default="build/exchange-reference")
    parser.add_argument("--schema-index", type=pathlib.Path)
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    if args.schema_index:
        print(schema_index(args.schema_index))
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
