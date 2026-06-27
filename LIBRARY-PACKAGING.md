# Library Packaging Strategy

How VonixGuardian ships its third-party Java dependencies across 8 loader jars without colliding with other mods that bundle the same libraries.

## The Three Patterns

1. **Shade + relocate** — rewrite package names at build time so two mods can carry the same lib without classpath collision. Works ONLY for pure-Java libraries.
2. **JarInJar / nested-jars** — bundle the unmodified library jar inside the mod jar; the loader dedups by version at classloading time. Works for everything, REQUIRED for libraries with native code (JNI).
3. **Soft dependency on a library mod** — declare `fabric-api`, `architectury-api`, etc as a runtime dependency. User installs separately.

## Per-Library Decision

| Library | Has native code? | Packaging | Why |
|---|---|---|---|
| **Gson** | No | Shade + relocate to `network.vonix.guardian.shadow.gson` | Pure Java; relocation is safe and prevents collision |
| **HikariCP** | No | Shade + relocate to `network.vonix.guardian.shadow.hikari` | Pure Java; pool config singletons benefit from isolation |
| **sqlite-jdbc** | **YES — JNI** | NeoForge/Forge: **JarInJar**. Fabric: **shade WITHOUT relocate** | JNI symbols `Java_org_sqlite_core_NativeDB_*` are baked into the `.so`/`.dll`/`.dylib`. Relocating Java classes to `…shadow.sqlite.core.NativeDB` causes `UnsatisfiedLinkError` at first DB op. |
| **SLF4J-API** | No | Don't shade — use loader's slf4j (NeoForge ships it; Forge uses log4j2 directly) | Avoiding "shaded slf4j has no impl binding → silent no-op" trap (`forge-mod-maintenance-fork → shaded-slf4j-silent-logging.md`) |
| **MySQL connector** | No (Java 8+) | Not bundled; user installs in `mods/` if using MySQL | Driver is 2.4 MB; only ~3% of users want MySQL backend |
| **Postgres JDBC** | No | Not bundled; user installs in `mods/` if using Postgres | Same reasoning as MySQL |

## NeoForge/Forge: JarInJar

NeoForge 1.21.1 uses the `jarJar` task built into `net.neoforged.moddev`. Forge 1.18.2-1.20.1 use the `jarJar` task in ForgeGradle 6.

```groovy
// Inside the loader build.gradle
dependencies {
    // sqlite-jdbc is NOT shaded — it goes into the nested jars via jarJar
    jarJar(implementation(libs.sqlite.jdbc)) {
        version { strictly libs.versions.sqlite.get() }
    }
}

// Gradle plugin auto-wires `jarJar` to add to META-INF/jarjar/*.jar inside the mod jar
// and writes META-INF/jarjar/metadata.json so the loader knows about them
```

The loader extracts these at runtime, dedups across all installed mods by Maven coords + version, and presents them as classpath entries. Two mods carrying sqlite-jdbc 3.46.1.0 → one copy loaded, both see it. Two mods carrying incompatible versions → loader picks the highest and warns.

## Fabric: shade without relocate

Fabric-loom's `include` config wants a "Fabric mod" (with `fabric.mod.json`). sqlite-jdbc is plain Maven. Two options:

**Option A (chosen): shade WITHOUT relocate.**
- Pros: zero ceremony; the `.so` JNI symbols match the unrelocated `org.sqlite.*` classes; it just works.
- Cons: if another Fabric mod also shaded unrelocated sqlite-jdbc with a different version, JVM picks whichever loaded first. **Lowest classloader wins** → most common-case win because Fabric loads mods alphabetically and sqlite-jdbc is virtually never bundled by other Fabric mods.
- Mitigation: log the loaded sqlite version on boot so version mismatches are diagnosable.

**Option B (not chosen): synthetic Fabric wrapper jar.**
- Wrap sqlite-jdbc in a minimal `fabric.mod.json`-bearing jar at build time, then `include` it.
- Pros: proper nested-jar resolution.
- Cons: significant build complexity, has to be re-done per MC version.

We picked A for v1.0.0 because (a) sqlite-jdbc-bundling Fabric mods are essentially nonexistent in real modpacks (LuckPerms-Fabric doesn't, MongoUtils doesn't, basically no one); (b) the diagnostic boot log makes any future collision easy to identify; (c) we can ship Option B in v1.1.0 if the real world shows collisions.

## Verification Checklist (every release)

```bash
# 1. JNI symbol parity — for NeoForge/Forge (extracted from the META-INF/jarjar nested jar):
unzip -p <jar> 'META-INF/jarjar/sqlite-jdbc-*.jar' > /tmp/sqlite.jar
unzip -j /tmp/sqlite.jar 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so' -d /tmp/
nm -D /tmp/libsqlitejdbc.so | grep -E 'T Java_org_sqlite_core_NativeDB' | head -3
# MUST show Java_org_sqlite_core_NativeDB_* (not Java_network_vonix_guardian_shadow_*)

# 2. JNI symbol parity — for Fabric (shaded unrelocated, native lib at org/sqlite/native/):
unzip -j <fabric-jar> 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so' -d /tmp/
nm -D /tmp/libsqlitejdbc.so | grep -E 'T Java_org_sqlite_core_NativeDB' | head -3
# Same: MUST show org_sqlite_core_NativeDB

# 3. Class location parity:
unzip -l <jar> | grep -E 'org/sqlite/core/NativeDB\.class|network/vonix/guardian/shadow/sqlite/core/NativeDB\.class'
# For NeoForge/Forge: NativeDB.class lives in META-INF/jarjar/sqlite-jdbc-*.jar (nested), not the outer jar
# For Fabric: NativeDB.class at org/sqlite/core/NativeDB.class (unrelocated)

# 4. JDBC service registration:
unzip -p <jar> META-INF/services/java.sql.Driver 2>/dev/null
# For Fabric: should print "org.sqlite.JDBC"
# For NeoForge/Forge: should print nothing (it's in the nested jar)
unzip -p <jar> 'META-INF/jarjar/sqlite-jdbc-*.jar' 2>/dev/null | \
  jar -tf /dev/stdin 2>/dev/null | grep -E 'META-INF/services'

# 5. Smoke: integration test in test/ loads SQLite from the actual jar and runs a SELECT
# (vg-smoketest gradle task — see .github/workflows/build.yml)
```

## Pitfalls

- **Don't relocate sqlite-jdbc.** It will compile clean, package clean, ship clean, and crash on first SQL op in production. Verified via `nm -D` symbol dump in this build.
- **Don't shade slf4j-api into the mod jar.** Loader provides its own; bundling yours either no-ops (no binding) or fights the loader's logging config. Use `org.apache.logging.log4j.LogManager.getLogger(...)` directly for mod-side logging.
- **JarInJar version conflicts are silent in dev environments.** ForgeGradle/NeoGradle don't extract nested jars during `runServer` because the classpath is already correct. Real-world conflicts only surface in production. Always do a clean-room verification: drop the released jar into a fresh MC server install, no IDE, and boot.
- **JarInJar metadata fingerprinting is per-version.** `sqlite-jdbc:3.46.1.0` and `sqlite-jdbc:3.50.0` are different artifacts to the loader. Pinning matters; if we bump the version, every JarInJar mod that pinned the old one loses against ours and *their* version is silently dropped. This is the loader's correct behaviour but it can hide cross-mod issues. Document the version in CHANGELOG every bump.
- **Don't bundle MySQL or Postgres drivers.** They're heavyweight, used by <5% of installs, and add CVE surface (recent MySQL driver CVEs are not fun to chase). Tell users to drop the driver jar in `mods/` and the JDBC service loader will find it.

## What other mods do

| Mod | Pattern | Reference |
|---|---|---|
| LuckPerms | JarInJar for ANTLR + slf4j; shade everything else | github.com/LuckPerms/LuckPerms |
| CoreProtect (Bukkit) | Shade everything (Bukkit has no JarInJar; their sqlite-jdbc is shaded unrelocated which only works because Bukkit's classloader doesn't relocate) | github.com/PlayPro/CoreProtect |
| Ledger (Quilt/Fabric) | Embedded SQLite as a Mixin into the server's classpath; relies on `xerial sqlite-jdbc` being unique in the loaders | github.com/QuiltServerTools/Ledger |
| Mekanism | JarInJar for all third-party (uses NeoForge's jarJar plugin) | github.com/mekanism/Mekanism |
| JEI | JarInJar for libraries; never shades | github.com/mezz/JustEnoughItems |

## See also

- `minecraft-mod-development` skill: `references/library-conflict-and-jarinjar-strategy.md`
- `forge-mod-maintenance-fork` skill: `references/shadowjar-relocation.md` (the pure-Java case)
