#!/usr/bin/env python3
"""Insert X7 TntPrimeMemory consultation into FabricMixinBridge.explosion in all 4 fabric cells."""
from pathlib import Path

BASE = Path("/tmp/vg-x7-wt")
FABRIC_BRIDGES = [
    (BASE / "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/FabricMixinBridge.java", "v1_18_2"),
    (BASE / "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/FabricMixinBridge.java", "v1_19_2"),
    (BASE / "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricMixinBridge.java", "v1_20_1"),
    (BASE / "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricMixinBridge.java", "v1_21_1"),
]

OLD = '''            Attribution attr = (source != null && FabricBootstrap.resolver != null)
                    ? FabricBootstrap.resolver.resolve(source, System.currentTimeMillis())
                    : Attribution.unknown(EntitySentinel.UNKNOWN);
'''

NEW = '''            String worldIdForPrime = WorldKey.of(level);
            // v1.3.1 X7: PrimedTnt loses its igniter by the time
            // Explosion#finalizeExplosion fires. Consult TntPrimeMemory
            // (populated by TntBlockMixin / PrimedTntEntityMixin) at the
            // entity's block position before falling back to the resolver.
            // Closes CoreProtect-parity gap G-CP-2.
            Attribution attr = null;
            if (source instanceof net.minecraft.world.entity.item.PrimedTnt) {
                network.vonix.guardian.core.Guardian gEarly = VonixGuardianFabric.guardian();
                if (gEarly != null) {
                    BlockPos originPos = source.blockPosition();
                    attr = network.vonix.guardian.core.attribution.UniversalAttribution
                            .resolveTntPrime(gEarly.tntPrimeMemory(), worldIdForPrime,
                                    originPos.getX(), originPos.getY(), originPos.getZ(),
                                    Sentinel.TNT);
                }
            }
            if (attr == null) {
                attr = (source != null && FabricBootstrap.resolver != null)
                        ? FabricBootstrap.resolver.resolve(source, System.currentTimeMillis())
                        : Attribution.unknown(EntitySentinel.UNKNOWN);
            }
'''

for f, ver in FABRIC_BRIDGES:
    t = f.read_text()
    if "X7: PrimedTnt loses its igniter" in t:
        print(f"SKIP {f.relative_to(BASE)} (already patched)")
        continue
    if OLD not in t:
        print(f"NO MATCH {f.relative_to(BASE)}")
        continue
    new = t.replace(OLD, NEW)
    # Adjust the "String worldId" declaration below to reuse worldIdForPrime
    new = new.replace("            String worldId = WorldKey.of(level);\n",
                       "            String worldId = worldIdForPrime;\n")
    f.write_text(new)
    print(f"PATCHED {f.relative_to(BASE)}")
