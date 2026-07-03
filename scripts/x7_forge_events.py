#!/usr/bin/env python3
"""Patch Forge cell ForgeEvents.onExplosionDetonate to consult TntPrimeMemory."""
from pathlib import Path

BASE = Path("/tmp/vg-x7-wt")

# (path, source-getter-line-suffix, world-getter-line, blockpos-center-line)
CELLS = [
    (BASE / "mc-1.18.2/forge/src/main/java/network/vonix/guardian/mc/v1_18_2/forge/ForgeEvents.java",
     "getSourceMob()", "WorldKey.of(ev.getWorld())"),
    (BASE / "mc-1.19.2/forge/src/main/java/network/vonix/guardian/mc/v1_19_2/forge/ForgeEvents.java",
     "getExploder()", "WorldKey.of((Level) ev.getLevel())"),
    (BASE / "mc-1.20.1/forge/src/main/java/network/vonix/guardian/mc/v1_20_1/forge/ForgeEvents.java",
     "getDirectSourceEntity()", "WorldKey.of((Level) ev.getLevel())"),
]

for path, getter, world_expr in CELLS:
    t = path.read_text()
    if "X7: PrimedTnt" in t:
        print(f"SKIP {path.relative_to(BASE)} (already patched)")
        continue

    old = (
        f"            Entity source = ev.getExplosion().{getter};\n"
        "            Attribution attr = (source != null && ForgeBootstrap.resolver != null)\n"
        "                    ? ForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())\n"
        "                    : Attribution.unknown(EntitySentinel.UNKNOWN);\n"
    )
    if old not in t:
        print(f"NO MATCH {path.relative_to(BASE)}")
        continue

    new = (
        f"            Entity source = ev.getExplosion().{getter};\n"
        "            // v1.3.1 X7: PrimedTnt loses its igniter by the time\n"
        "            // ExplosionEvent.Detonate fires. Consult TntPrimeMemory\n"
        "            // (populated by TntBlockMixin / PrimedTntEntityMixin) at\n"
        "            // the entity's block position before falling back to the\n"
        "            // resolver. Closes CoreProtect-parity gap G-CP-2.\n"
        f"            String worldIdForPrime = {world_expr};\n"
        "            Attribution attr = null;\n"
        "            if (source instanceof net.minecraft.world.entity.item.PrimedTnt) {\n"
        "                Guardian gEarly = g();\n"
        "                if (gEarly != null) {\n"
        "                    BlockPos originPos = source.blockPosition();\n"
        "                    attr = network.vonix.guardian.core.attribution.UniversalAttribution\n"
        "                            .resolveTntPrime(gEarly.tntPrimeMemory(), worldIdForPrime,\n"
        "                                    originPos.getX(), originPos.getY(), originPos.getZ(),\n"
        "                                    Sentinel.TNT);\n"
        "                }\n"
        "            }\n"
        "            if (attr == null) {\n"
        "                attr = (source != null && ForgeBootstrap.resolver != null)\n"
        "                        ? ForgeBootstrap.resolver.resolve(source, System.currentTimeMillis())\n"
        "                        : Attribution.unknown(EntitySentinel.UNKNOWN);\n"
        "            }\n"
    )
    t = t.replace(old, new)
    # Reuse worldIdForPrime for the "String worldId = ..." line to avoid duplicate.
    t = t.replace(
        f"            String worldId = {world_expr};\n",
        "            String worldId = worldIdForPrime;\n"
    )
    path.write_text(t)
    print(f"PATCHED {path.relative_to(BASE)}")
