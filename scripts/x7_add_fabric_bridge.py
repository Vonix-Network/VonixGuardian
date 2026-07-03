#!/usr/bin/env python3
"""Add X7 recordTntPrimePlayer bridge method to all 4 fabric FabricMixinBridge.java files."""
from pathlib import Path

BASE = Path("/tmp/vg-x7-wt")
FABRIC_BRIDGES = [
    BASE / "mc-1.18.2/fabric/src/main/java/network/vonix/guardian/mc/v1_18_2/fabric/FabricMixinBridge.java",
    BASE / "mc-1.19.2/fabric/src/main/java/network/vonix/guardian/mc/v1_19_2/fabric/FabricMixinBridge.java",
    BASE / "mc-1.20.1/fabric/src/main/java/network/vonix/guardian/mc/v1_20_1/fabric/FabricMixinBridge.java",
    BASE / "mc-1.21.1/fabric/src/main/java/network/vonix/guardian/mc/v1_21_1/fabric/FabricMixinBridge.java",
]

ADDITION = '''
    // ==================================================================== X7 TNT-prime memory
    // v1.3.1 X7: record the actor priming a TNT block so the eventual
    // explosion-detonate handler can attribute correctly. See
    // network.vonix.guardian.core.attribution.TntPrimeMemory.

    /**
     * Record a player as the priming actor for the TNT block at {@code pos}.
     *
     * <p>Called from {@code TntBlockMixin.explode(Level,BlockPos,LivingEntity)}
     * HEAD when the igniter is a Player, and from
     * {@code PrimedTntEntityMixin.<init>} TAIL when the igniter argument on
     * the constructor is a Player.
     */
    public static void recordTntPrimePlayer(Level level, BlockPos pos, Player player) {
        try {
            Guardian g = VonixGuardianFabric.guardian();
            if (g == null || level == null || pos == null || player == null) return;
            long now = System.currentTimeMillis();
            g.tntPrimeMemory().record(
                    WorldKey.of(level), pos.getX(), pos.getY(), pos.getZ(),
                    network.vonix.guardian.core.attribution.TntPrimeMemory.PrimeRecord.player(
                            player.getUUID(), player.getName().getString(), now));
        } catch (Throwable t) {
            warn("recordTntPrimePlayer", t);
        }
    }

'''

# Anchor: dispense method's closing brace
anchor_old = '''    /** DispenserBlock#dispenseFrom → DISPENSE. */
    public static void dispense(Level level, BlockPos pos) {
        try {
            EventSubmitter s = sub();
            if (s == null || level == null || pos == null) return;
            s.submitDispense(null, "#dispenser", WorldKey.of(level),
                    pos.getX(), pos.getY(), pos.getZ(), "minecraft:dispenser", "world:dispense");
        } catch (Throwable t) {
            warn("dispense", t);
        }
    }
'''

anchor_new = anchor_old + ADDITION

for f in FABRIC_BRIDGES:
    t = f.read_text()
    if "recordTntPrimePlayer" in t:
        print(f"SKIP {f.relative_to(BASE)} (already present)")
        continue
    if anchor_old not in t:
        print(f"NO MATCH {f.relative_to(BASE)}")
        continue
    new = t.replace(anchor_old, anchor_new)
    f.write_text(new)
    print(f"WROTE {f.relative_to(BASE)}")
