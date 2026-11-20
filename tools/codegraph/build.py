"""Build the repository code graph with the pinned indexer toolchain."""

import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import subprocess
import sys
from tempfile import TemporaryDirectory

from query import GRAPH, ROOT, fingerprint, source_paths

TOOLS = Path(__file__).resolve().parent
IDENTIFIER = re.compile(r"(?:[^\W\d]|\$)[\w$]*")


def scip_occurrences(index, prefix, sources):
    output = subprocess.check_output(["node", TOOLS / "read-scip.mjs", index], text=True)
    lines = {}
    for line in output.splitlines():
        item = json.loads(line)
        path = prefix + item["path"]
        if path not in sources:
            continue
        row, column = item["line"], item["column"]
        if path not in lines:
            lines[path] = (ROOT / path).read_text().splitlines()
        if row > len(lines[path]):
            continue
        source = lines[path][row - 1]
        # scip-go uses byte columns and scip-typescript uses UTF-16 code units.
        encoding, width = ("utf-8", 1) if prefix else ("utf-16-le", 2)
        text = source.encode(encoding)[column * width :].decode(encoding, errors="ignore")
        match = IDENTIFIER.match(text)
        if not match:
            continue
        symbol = item["symbol"]
        if symbol.startswith("local "):
            symbol = f"{path}:{symbol}"
        yield symbol, match[0], (path, row), item["def"]


def kotlin_occurrences(sources):
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin

    parser = Parser(Language(tree_sitter_kotlin.language()))
    declarations = {
        "class_declaration", "object_declaration", "function_declaration",
        "variable_declaration", "class_parameter", "parameter", "type_parameter",
        "type_alias", "enum_entry",
    }
    for path in sources:
        if not path.endswith((".kt", ".kts")):
            continue
        source = (ROOT / path).read_bytes()
        pending = [parser.parse(source).root_node]
        while pending:
            node = pending.pop()
            pending.extend(reversed(node.named_children))
            if node.type != "identifier":
                continue
            name = source[node.start_byte : node.end_byte].decode().strip("`")
            parent = node.parent
            first_identifier = next(
                (child for child in parent.named_children if child.type == "identifier"), None
            )
            declaration = parent.child_by_field_name("name") or first_identifier
            is_definition = parent.type in declarations and node == declaration
            yield f"kotlin:{name}", name, (path, node.start_point.row + 1), is_definition


def run(*args, cwd=ROOT):
    subprocess.run(args, cwd=cwd, check=True, stdout=sys.stderr)


def build(sources):
    with TemporaryDirectory() as directory:
        work = Path(directory)
        go_index, js_index = work / "go.scip", work / "js.scip"
        run(
            "scip-go", "--skip-implementations", "--module-version", "dev",
            "--repository-remote", "github.com/julienbrunet/mijote",
            "--output", go_index, cwd=ROOT / "server",
        )
        run(
            TOOLS / "node_modules/.bin/scip-typescript", "index", "--no-progress-bar",
            "--output", js_index, TOOLS / "jsconfig.json",
        )

        definitions = defaultdict(set)
        references = defaultdict(set)
        for occurrences in (
            scip_occurrences(go_index, "server/", sources),
            scip_occurrences(js_index, "", sources),
            kotlin_occurrences(sources),
        ):
            for symbol, name, location, is_definition in occurrences:
                if is_definition:
                    definitions[symbol].add((name, location))
                else:
                    references[symbol].add(location)

    paths = sorted(sources)
    file_ids = {path: index for index, path in enumerate(paths)}
    groups = defaultdict(set)
    for symbol, locations in definitions.items():
        by_name = defaultdict(set)
        for name, (path, row) in locations:
            by_name[name].add((file_ids[path], row))
        refs = tuple(sorted((file_ids[path], row) for path, row in references[symbol]))
        for name, defs in by_name.items():
            groups[name].add((tuple(sorted(defs)), refs))

    def json_line(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))

    header = {"version": 1, "fingerprint": fingerprint(sources), "files": paths}
    return "\n".join([
        json_line(header),
        *(json_line([name, sorted(groups[name])]) for name in sorted(groups)),
        "",
    ])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if the checked-in graph differs")
    args = parser.parse_args()
    graph = build(source_paths())
    if args.check:
        if not GRAPH.exists() or GRAPH.read_text() != graph:
            sys.exit("Code graph is stale. Regenerate with python3 tools/codegraph/build.py.")
    else:
        GRAPH.write_text(graph)


if __name__ == "__main__":
    main()
