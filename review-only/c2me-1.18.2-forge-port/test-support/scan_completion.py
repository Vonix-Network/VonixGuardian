#!/usr/bin/env python3
"""Fail if active completion markers remain in Java/docs/test sources."""
from __future__ import annotations

import re
import sys
from pathlib import Path

MARKERS = re.compile(
    r"(?<![A-Za-z0-9_])TODO(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])FIXME(?![A-Za-z0-9_])|"
    r"(?<![A-Za-z0-9_])XXX(?![A-Za-z0-9_])|VanillaCopy|VanilaCopy|"
    r"not-implemented|not implemented|"
    r"(?<![A-Za-z0-9_])deferred(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])stub(?![A-Za-z0-9_])|"
    r"(?<![A-Za-z0-9_])placeholder(?![A-Za-z0-9_])",
    re.IGNORECASE,
)
EMPTY_CATCH = re.compile(r"catch\s*\([^)]+\)\s*\{\s*\}")


def scan(root: Path) -> int:
    hits = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        if path.suffix not in {".java", ".md", ".yml", ".toml", ".json"}:
            continue
        if "build/" in path.as_posix():
            continue
        text = path.read_text(errors="ignore")
        for index, line in enumerate(text.splitlines(), 1):
            if MARKERS.search(line) or EMPTY_CATCH.search(line):
                hits.append(f"{path}:{index}:{line.strip()}")
    if hits:
        print("COMPLETION_SCAN_FAIL")
        for hit in hits:
            print(hit)
        return 1
    print("COMPLETION_SCAN_PASS")
    return 0


if __name__ == "__main__":
    target = Path(sys.argv[1] if len(sys.argv) > 1 else "src")
    raise SystemExit(scan(target))
