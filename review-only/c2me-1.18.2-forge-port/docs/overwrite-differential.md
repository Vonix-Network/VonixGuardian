# Official Forge 40.3.11 overwrite differential

JUnit tests run against mapped Forge `1.18.2-40.3.11` classes. Mixins are not applied in that JVM, so retained same-semantics overwrites are proven by executing the production helper the mixin calls, or by matching official constructors and public methods.

`OfficialOverwriteDifferentialTest` is the executed suite.

## Proven against mapped classes

| Path | Cases | Claim |
| --- | --- | --- |
| `ImprovedNoiseMath` vs official `ImprovedNoise.noise` | 1_000_000 | bit-identical `double` bits |
| `PerlinNoiseMath.wrap` vs official `PerlinNoise.wrap` | 1_000_000 | exact `double` equality |
| `SimplifiedAtomicSimpleRandom` vs official `LegacyRandomSource` / `SingleThreadedRandomSource` | 1_000_000 | same `nextInt` stream after `setSeed` |
| Factory seed `Mth.getSeed(x,y,z) ^ seed` and `hashCode ^ seed` vs official `LegacyPositionalRandomFactory` | 1_000_000 | same first `nextLong` / `nextInt` |
| `ResourceLocation` string form `namespace:path` | 1_000_000 | matches official `toString` |
| First-non-null list vs array (SequenceRule / MaterialRuleList shape; string stand-ins so the test JVM does not bootstrap `Registry`) | 1_000_000 | same selected value |
| `EndBiomeDecision` vs official `TheEndBiomeSource.getHeightValue` control flow | 1_000_000 | same branch |
| `CompoundTag` copy using a FastUtil map | 100_000 | `equals` and `getAsString` match official `copy` |
| `Util.sequence` vs `Combinators.collect` | 20_000 lists | same completed lists |

## Not claimed bit-for-bit, still registered as feature ports

These overwrites change scheduling, I/O, autosave, or concurrency. They are not same-semantics math. They stay registered because removing them would disable the advertised feature. They are experimental, not proven vanilla-equivalent:

- `threading.worldgen.MixinChunkStatus.generate`
- `fixes.general.threading.MixinChunkHolder`
- `threading.chunkio` save/load overwrites
- aquifer and End biome *cache* wrapper (decision function is proven; Holder registry bootstrap is not)
- enhanced autosave `MinecraftServer` tick overwrite
- no-tick ticket tracker overwrites
- `Util.sequenceFailFast` under failure (success path is compared)
- `ShufflingList.shuffle` new-instance behavior (JUnit sees official in-place shuffle)

No overwrite was removed solely because a 1_000_000-case Holder/world bootstrap was impractical. No production-ready or 1:1 C2ME parity claim is made.
