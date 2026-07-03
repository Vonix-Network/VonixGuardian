#!/usr/bin/env python3
"""Undo overzealous 'String worldId = worldIdForPrime' replacements and only
keep it at the explosion detonate site."""
from pathlib import Path

BASE = Path("/tmp/vg-x7-wt")

# For each cell, the correct world expression per version.
CELLS = [
    (BASE / "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeEvents.java",
     "WorldKey.of(ev.getWorld())"),
    (BASE / "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeEvents.java",
     "WorldKey.of((Level) ev.getLevel())"),
    (BASE / "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeEvents.java",
     "WorldKey.of((Level) ev.getLevel())"),
]

for path, world_expr in CELLS:
    t = path.read_text()
    # Replace ALL "String worldId = worldIdForPrime;" back to the real expression
    n = t.count("String worldId = worldIdForPrime;")
    if n == 0:
        print(f"SKIP {path.relative_to(BASE)}")
        continue
    t = t.replace("String worldId = worldIdForPrime;", f"String worldId = {world_expr};")
    # This changed all N callsites. The explosion detonate site KEEPS worldIdForPrime as
    # its own local declared earlier - we don't need to reuse it there, just use worldId.
    # But now inside the explosion handler, we have both worldIdForPrime (used before attr resolution)
    # and worldId (used later). That's fine - worldIdForPrime is a local var, unused after.
    # Actually cleaner: remove the `String worldIdForPrime = ...;` line since we can reuse
    # worldId if we hoist it. Or just keep both — dead assignment warning at most.
    path.write_text(t)
    print(f"REVERTED {n}× in {path.relative_to(BASE)}")
