#!/usr/bin/env python3
"""Register TntBlockMixin + PrimedTntEntityMixin in the Fabric mixin JSONs."""
import json
from pathlib import Path

BASE = Path("/tmp/vg-x7-wt")
JSONS = [
    BASE / "mc-1.18.2/fabric/src/main/resources/vg.mixins.json",
    BASE / "mc-1.19.2/fabric/src/main/resources/vg.mixins.json",
    BASE / "mc-1.20.1/fabric/src/main/resources/vg.mixins.json",
    BASE / "mc-1.21.1/fabric/src/main/resources/vg.mixins.json",
]

NEW = ["PrimedTntEntityMixin", "TntBlockMixin"]

for f in JSONS:
    data = json.loads(f.read_text())
    mixins = data.get("mixins", [])
    changed = False
    for m in NEW:
        if m not in mixins:
            mixins.append(m)
            changed = True
    if changed:
        mixins.sort()
        data["mixins"] = mixins
        f.write_text(json.dumps(data, indent=2) + "\n")
        print(f"UPDATED {f.relative_to(BASE)}")
    else:
        print(f"SKIP {f.relative_to(BASE)}")
