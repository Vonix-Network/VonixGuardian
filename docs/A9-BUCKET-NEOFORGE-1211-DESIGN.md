# A9 — NeoForge 1.21.1 `BUCKET_EMPTY` / `BUCKET_FILL` mixin design

> **Status:** design note only. **This wave (W2-02) does not ship code.** The
> corresponding handlers on 1.21.1/neoforge remain unwired and are tracked by
> the pre-existing `TODO(1.0.5)` in `NeoForgeEvents.java` (~line 636).

## 1. Problem statement

VonixGuardian relies on `net.minecraftforge.event.entity.player.FillBucketEvent`
in the three legacy cells (1.18.2, 1.19.2, 1.20.1) to log both bucket fills
(water/lava/powder-snow scooped into a bucket) and bucket empties (liquid
placed in world). That single event drives both `submitBucketFill` and
`submitBucketEmpty`.

**On NeoForge 1.21.1 the event no longer exists.** NeoForge removed
`FillBucketEvent` during the 1.20 → 1.21 refactor of item-use hooks. The
underlying logic moved into vanilla `BucketItem.use(...)` /
`BucketItem.emptyContents(...)` / `MilkBucketItem.finishUsingItem(...)` with
no replacement event on the NeoForge bus. `UseItemOnBlockEvent` and
`BlockEvent.FluidPlaceBlockEvent` fire adjacent to bucket use but neither
gives us both directions with the right coordinates and item id:

| Event                              | Direction   | Coords     | Held-item id available | Verdict          |
| ---------------------------------- | ----------- | ---------- | ---------------------- | ---------------- |
| `UseItemOnBlockEvent` (pre-use)    | Both        | Click pos  | Yes                    | Fires for every  |
|                                    |             |            |                        | right-click; too |
|                                    |             |            |                        | noisy, can't     |
|                                    |             |            |                        | tell success.    |
| `BlockEvent.FluidPlaceBlockEvent`  | Empty only  | Place pos  | No (level-driven)      | Misses fills;    |
|                                    |             |            |                        | can't attribute. |
| `PlayerInteractEvent.RightClickItem`| Neither    | Player pos | Yes                    | Fires before     |
|                                    |             |            |                        | vanilla resolves |
|                                    |             |            |                        | the raytrace.    |
| `EntityItemPickupEvent`            | Fill only   | Player pos | No                     | Wrong entity,    |
|                                    | (indirect)  |            |                        | wrong coords.    |

No combination reconstructs CoreProtect-parity data without duplication or
gaps. **A mixin is the correct answer.**

## 2. Existing mixin infrastructure — none yet

Search of `/tmp/vg-w2-02-wt/mc-1.21.1/neoforge/`:

```text
find … -name "*mixin*" -o -name "*.mixins.json"   # (no matches)
grep -rn "@Mixin\|mixinConfigs" mc-1.21.1/neoforge   # (no matches)
```

VonixGuardian **has never used mixins in any cell**. Every existing feature is
built on the vanilla/Forge/NeoForge event bus. Adding a mixin to the 1.21.1
NeoForge cell therefore requires:

1. A new `guardian.mixins.json` under `src/main/resources/`.
2. Adding the mixin config to the NeoForge mods.toml (`mixins = [{ config = "guardian.mixins.json" }]`).
3. Wiring the mixin gradle plugin (already available via NeoForge's
   `neoforge` gradle facilities; no third-party plugin needed).
4. A `network.vonix.guardian.mc.v1_21_1.neoforge.mixin` package.

None of that exists today — that alone is a reason to split the work into its
own wave and not sneak it in under W2-02.

## 3. Recommended mixin plan

Two mixins are needed to cover the two bucket paths that vanilla still owns:

### 3.1 `MixinBucketItem` — water / lava / powder-snow buckets

**Target:** `net.minecraft.world.item.BucketItem`

**Method:** `use(Level level, Player player, InteractionHand hand)` — returns
`InteractionResultHolder<ItemStack>`.

**Injection points:**

- `@Inject(method="use", at=@At("RETURN"))` — capture the `InteractionResultHolder`
  and only submit when `.getResult() == InteractionResult.CONSUME` /
  `SUCCESS`. Read `player.level().clip(...)` **once** at entry (via `@Local`)
  to get the exact `BlockHitResult` the vanilla method already computed, and
  branch:
  - If the item on entry was `Items.BUCKET` (empty) → this is a **FILL** on
    `hit.getBlockPos()` with the source-fluid id from the state that was there
    at entry (`level.getBlockState(hit.getBlockPos())` captured pre-vanilla-run
    via a second `@Inject(at=@At("HEAD"))` writing to a
    `ThreadLocal<BlockPos+String>`).
  - If the item on entry was a *filled* bucket (`WaterBucket`, `LavaBucket`,
    `PowderSnowBucket`, plus modded `BucketItem` subclasses) → **EMPTY** on
    the block adjacent to `hit` (use `hit.getBlockPos().relative(hit.getDirection())`
    when the target block is non-replaceable).

**Why not `@Redirect` on `BucketItem.emptyContents` / `BucketItem.getFluid`?**
Redirect changes control flow — we do NOT want to change vanilla behaviour, only
observe. `@Inject` at `RETURN` with `CallbackInfoReturnable` is strictly a read.

**Attribution:** `player.getUUID()` and `player.getName().getString()` are both
available directly on the mixin frame; no resolver dance needed. World id via
the existing `WorldKey.of(player.level())`.

**Skeleton:**

```java
package network.vonix.guardian.mc.v1_21_1.neoforge.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import network.vonix.guardian.core.event.EventSubmitter;
import network.vonix.guardian.mc.v1_21_1.common.WorldKey;
import network.vonix.guardian.mc.v1_21_1.neoforge.VonixGuardianNeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BucketItem.class)
public abstract class MixinBucketItem {

    /** Per-thread capture of pre-use context so RETURN can attribute the fill/empty. */
    private static final ThreadLocal<BlockPos> VG$CLICKED_POS = new ThreadLocal<>();
    private static final ThreadLocal<String>   VG$PRE_BLOCK_ID = new ThreadLocal<>();

    @Inject(method = "use", at = @At("HEAD"))
    private void vg$captureClicked(Level level, Player player, InteractionHand hand,
                                   CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        HitResult hit = /* replicate vanilla's getPlayerPOVHitResult call */;
        if (hit instanceof BlockHitResult bhr) {
            BlockPos pos = bhr.getBlockPos();
            VG$CLICKED_POS.set(pos);
            VG$PRE_BLOCK_ID.set(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(pos).getBlock()).toString());
        }
    }

    @Inject(method = "use", at = @At("RETURN"))
    private void vg$emit(Level level, Player player, InteractionHand hand,
                         CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        try {
            InteractionResultHolder<ItemStack> r = cir.getReturnValue();
            if (r.getResult() != InteractionResult.CONSUME
                && r.getResult() != InteractionResult.SUCCESS) return;
            EventSubmitter s = VonixGuardianNeoForge.guardian() == null
                    ? null : VonixGuardianNeoForge.guardian().submitter();
            if (s == null) return;
            BlockPos pos = VG$CLICKED_POS.get();
            if (pos == null) return;
            String worldId = WorldKey.of(player.level());
            ItemStack held = player.getItemInHand(hand);
            boolean wasEmpty = ((BucketItem)(Object)this).equals(Items.BUCKET);
            if (wasEmpty) {
                s.submitBucketFill(player.getUUID(), player.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(),
                        VG$PRE_BLOCK_ID.get(), null);
            } else {
                String fluid = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(((BucketItem)(Object)this)).toString();
                s.submitBucketEmpty(player.getUUID(), player.getName().getString(),
                        worldId, pos.getX(), pos.getY(), pos.getZ(),
                        fluid, null);
            }
        } finally {
            VG$CLICKED_POS.remove();
            VG$PRE_BLOCK_ID.remove();
        }
    }
}
```

*(Skeleton for design review — the actual implementation should replace the
`/* replicate … */` comment with a call to the same `Item.getPlayerPOVHitResult`
helper vanilla uses, or a `@Local` capture of the vanilla-computed `HitResult`
via `@ModifyExpressionValue` on a Mixin Extras dependency if that's already on
the classpath.)*

### 3.2 `MixinMilkBucketItem` — milk buckets

**Target:** `net.minecraft.world.item.MilkBucketItem`

**Method:** `finishUsingItem(ItemStack stack, Level level, LivingEntity entity)`

**Rationale:** Milk buckets don't route through `BucketItem.use` — they use the
food-consume path (`Item.finishUsingItem`). They still count as a **fill**
(bucket ⇒ empty bucket) semantically identical to CoreProtect's
`BUCKET_EMPTY` for milk-drink.

**Injection:** `@Inject(method="finishUsingItem", at=@At("RETURN"))` filtering
for `entity instanceof Player`. Submit as `submitBucketEmpty` with fluid id
`"minecraft:milk_bucket"` and coords = drinker position (milk has no world
target — cf. CoreProtect logs milk drinks at the player's block position).

## 4. Fallback / defence-in-depth

Even after the mixin lands, keep the current TODO comment noting that:
- Modded buckets that don't extend `BucketItem` (Mekanism gas buckets, e.g.)
  won't be caught by the mixin — they need per-mod extension.
- Dispensers filling buckets go through `DispenseItemBehavior` — that path
  should be caught by the future `DISPENSE` mixin (see W2-02 inline TODO for
  Burn/Ignite/Fade/Form/Spread/Dispense/LeavesDecay).

## 5. Effort estimate

- 1 mixin config file + gradle wiring: ~0.5d
- 2 mixin classes (BucketItem + MilkBucketItem): ~1d
- End-to-end test on 1.21.1 NeoForge dev server (fill/empty water, lava,
  powder snow, milk drink): ~0.5d
- Reconcile with the future FORM/DISPENSE mixin wave so we don't double-log
  when the dispenser path lands: ~0.5d planning

**Total: ~2.5 dev-days** for a properly reviewed, tested, isolated wave.

## 6. Why we are NOT shipping this now

1. VonixGuardian has zero existing mixin infrastructure — introducing it under
   a broader-scope wiring wave (W2-02) buries the risk.
2. The mixin surface for `A8` (Burn/Ignite/Fade/Form/Spread/Dispense/
   LeavesDecay) is much larger and would benefit from being planned alongside
   the bucket mixin so we introduce the mixin config + gradle wiring **once**.
3. W2-02's parent task explicitly says "recommend mixin approach ok, we won't
   ship the mixin here."

The design lives here so the future wave (candidate name **W3-A9-mixin**) can
pick this up cold.
