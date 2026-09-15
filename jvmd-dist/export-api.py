#!/usr/bin/env python3
"""Export the exact MCP input schemas and their core method mappings for client authors."""
import argparse
import json
from pathlib import Path
import re

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--check", action="store_true", help="Fail if the committed catalog is stale")
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
source = root / "jvmd-mcp/src/main/java/dev/jvmd/mcp/McpTools.java"
match = re.search(r'CATALOG_JSON = ("(?:[^"\\]|\\.)*");', source.read_text())
if match is None:
    raise SystemExit("Cannot locate the frozen MCP catalog in McpTools.java")
catalog = json.loads(json.loads(match.group(1)))
output = json.dumps(catalog, indent=2, ensure_ascii=False) + "\n"
target = root / "docs/api/mcp-tools.json"
if args.check:
    if not target.is_file() or target.read_text() != output:
        raise SystemExit("API catalog is stale; run python3 jvmd-dist/export-api.py")
    print(f"API catalog matches all {len(catalog)} tools")
else:
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(output)
    print(f"Exported {len(catalog)} tools to {target.relative_to(root)}")
