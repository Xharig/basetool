"""Checks that every relative link in the exchange documentation works on the published site.

A relative link must stay inside docs/exchange, which is all the site publishes, and point at a file
that exists; links into the site's generated parts (the OpenAPI reference and the schema copies) are
checked against their sources in the repository. Anchors and absolute URLs are not followed.
"""

import argparse
import pathlib
import re
import sys
import tempfile

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")

GENERATED = {
    "reference": "ingest/src/main/resources/api/exchange-v1.openapi.json",
    "reference/exchange-v1.openapi.json": "ingest/src/main/resources/api/exchange-v1.openapi.json",
    "schemas": "ingest/src/main/resources/exchange/v1/schemas",
}


def broken_links(repo: pathlib.Path) -> list[str]:
    """Returns every relative link under docs/exchange that would not work on the site.

    Args:
        repo: the repository root.

    Returns:
        One ``file: link (reason)`` line per broken link.
    """
    docs = (repo / "docs" / "exchange").resolve()
    broken = []
    for page in sorted(docs.rglob("*.md")):
        text = page.read_text(encoding="utf-8")
        for target in LINK.findall(text):
            if re.match(r"^[a-z][a-z0-9+.-]*:", target) or target.startswith("#"):
                continue
            where = f"{page.relative_to(repo.resolve()).as_posix()}: {target}"
            resolved = (page.parent / target.split("#", 1)[0]).resolve()
            if not resolved.is_relative_to(docs):
                broken.append(f"{where} (leaves the published site)")
                continue
            key = resolved.relative_to(docs).as_posix()
            if key in GENERATED:
                if not (repo / GENERATED[key]).exists():
                    broken.append(f"{where} (source missing)")
            elif not resolved.exists():
                broken.append(f"{where} (missing)")
    return broken


def selftest() -> None:
    """Proves the checker finds each kind of broken link and passes good ones."""
    with tempfile.TemporaryDirectory() as tmp:
        repo = pathlib.Path(tmp).resolve()
        docs = repo / "docs" / "exchange"
        docs.mkdir(parents=True)
        (repo / "docs" / "other.md").write_text("# other", encoding="utf-8")
        (docs / "a.md").write_text(
            "[ok](b.md#top) [web](https://example.org) [anchor](#x) [gone](missing.md)"
            " [ref](reference/) [out](../other.md)",
            encoding="utf-8",
        )
        (docs / "b.md").write_text("# b", encoding="utf-8")
        found = broken_links(repo)
        assert found == [
            "docs/exchange/a.md: missing.md (missing)",
            "docs/exchange/a.md: reference/ (source missing)",
            "docs/exchange/a.md: ../other.md (leaves the published site)",
        ], found
        spec = repo / "ingest/src/main/resources/api"
        spec.mkdir(parents=True)
        (spec / "exchange-v1.openapi.json").write_text("{}", encoding="utf-8")
        found = broken_links(repo)
        assert len(found) == 2, found
    print("selftest ok")


def main() -> int:
    """Runs the check, or its self-test with --selftest.

    Returns:
        0 when every link works, 1 otherwise.
    """
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--selftest", action="store_true")
    args = parser.parse_args()
    if args.selftest:
        selftest()
        return 0
    repo = pathlib.Path(__file__).resolve().parents[2]
    broken = broken_links(repo)
    for line in broken:
        print(f"broken link: {line}")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main())
