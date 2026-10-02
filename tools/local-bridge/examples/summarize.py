#!/usr/bin/env python3
"""Example processor contract: component path, output directory; no third-party dependencies."""
import json
from pathlib import Path
import sys

component = json.loads(Path(sys.argv[1]).read_text())
content = component["content"]
node = content["component"]
output = Path(sys.argv[2])
summary = {"hash": component["hash"], "package": content.get("package"), "tag": node.get("tag"),
           "hasTestTag": bool(node.get("tag")), "enabled": node.get("enabled"),
           "treePath": content["tree"].get("path", []), "coverage": content.get("coverage")}
(output / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
# Rendering as plain text avoids interpreting host labels as HTML or Markdown commands.
(output / "attributes.txt").write_text("Component attributes\n\n" + json.dumps(node, ensure_ascii=False, indent=2) + "\n")
