# Mijote agent instructions

Follow the project rules in [CLAUDE.md](CLAUDE.md), including the language, testing and delivery conventions.

## Code navigation

Use the checked-in code graph first when locating symbols or tracing code references. Run these commands from the repository root.

```sh
python3 tools/codegraph/query.py status
python3 tools/codegraph/query.py symbols recipe --limit 8
python3 tools/codegraph/query.py defs GenerateMenu --path 'server/*'
python3 tools/codegraph/query.py refs GenerateMenu --path 'server/*' --limit 10
```

Use the returned paths and line numbers to read focused source sections. Use `rg` for literals, unsupported files, missing results, or sources changed since the graph was generated. Kotlin reference results match names and can include unrelated symbols with the same name. Confirm matches in source.

Each checkout includes its own graph. Reuse it for exploration without installing or running indexers. Refresh it when changing indexed sources, following the instructions in [CLAUDE.md](CLAUDE.md).
