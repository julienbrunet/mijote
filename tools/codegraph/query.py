"""Find source symbols, definitions and references in the checked-in code graph."""

import argparse
from fnmatch import fnmatchcase
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
GRAPH = Path(__file__).with_name("graph.jsonl")


def source_paths():
    files = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT
    ).decode().split("\0")
    sources = set()
    for path in files:
        if (
            (path.startswith("server/") and path.endswith(".go"))
            or (path.startswith("android/") and path.endswith((".kt", ".kts")))
            or (path.startswith(("site/", "server/internal/admin/static/")) and path.endswith(".js"))
        ) and (ROOT / path).is_file():
            sources.add(path)
    return sources


def fingerprint(sources):
    digest = hashlib.sha256()
    for path in sorted(sources | {"server/go.mod", "server/go.sum"}):
        digest.update(path.encode() + b"\0" + hashlib.sha256((ROOT / path).read_bytes()).digest())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("status", "symbols", "defs", "refs"))
    parser.add_argument("name", nargs="?")
    parser.add_argument("--path", metavar="GLOB", help="filter by definition path, e.g. 'server/*'")
    parser.add_argument("--limit", type=int, default=15)
    args = parser.parse_args()
    if args.command != "status" and not args.name:
        parser.error("a symbol name is required")
    if args.limit < 1:
        parser.error("--limit must be positive")

    try:
        with GRAPH.open() as index:
            header = json.loads(next(index))
            if header["version"] != 1:
                sys.exit("Unsupported code graph format.")
            if args.command == "status":
                if fingerprint(source_paths()) != header["fingerprint"]:
                    sys.exit("Code graph is stale for this checkout.")
                print("Code graph is current.")
                return

            files = header["files"]
            results = set()
            for line in index:
                name, groups = json.loads(line)
                if args.command == "symbols":
                    if args.name.casefold() not in name.casefold():
                        continue
                elif name != args.name:
                    continue
                for definitions, refs in groups:
                    selected = [
                        location for location in definitions
                        if not args.path or fnmatchcase(files[location[0]], args.path)
                    ]
                    if not selected:
                        continue
                    locations = refs if args.command == "refs" else selected
                    results.update((name, files[file_id], row) for file_id, row in locations)

            if not results:
                sys.exit(1)
            for name, path, row in sorted(results)[: args.limit]:
                print(f"{path}:{row}\t{name}")
            if len(results) > args.limit:
                print(f"{len(results) - args.limit} more results", file=sys.stderr)
    except FileNotFoundError:
        sys.exit("Code graph is unavailable.")


if __name__ == "__main__":
    main()
