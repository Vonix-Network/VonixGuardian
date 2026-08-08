# Library Packaging Strategy

How VonixGuardian ships its third-party Java dependencies across 8 loader jars without colliding with other mods that bundle the same libraries.

## The Three Patterns

1. **Shade + relocate** — rewrite package names at build time so two mods can carry the same lib without classpath collision. Works ONLY for pure-Java libraries.
2. **JarInJar / nested-jars** — bundle the unmodified library jar inside the mod jar; the loader dedups by version at classloading time. Works for everything, REQUIRED for libraries with native code (JNI).
3. **Soft dependency on a library mod** — declare `fabric-api`, `architectury-api`, etc as a runtime dependency. User installs separately.

## Per-Library Decision

| Library | Has native code? | Packaging | Why |
|---|---|---|---|
| **Gson** | No | Fabric: **JarInJar**. Forge/NeoForge: shade + relocate to `network.vonix.guardian.shadow.gson` | Fabric uses Loom's supported nested-jar path; relocation remains safe on the Forge family |
| **HikariCP** | No | Fabric: **JarInJar**. Forge/NeoForge: shade + relocate to `network.vonix.guardian.shadow.hikari` | Fabric excludes the loader-owned slf4j API from the nested Hikari dependency |
| **sqlite-jdbc** | **YES — JNI** | **JarInJar on every loader** | JNI symbols `Java_org_sqlite_core_NativeDB_*` are baked into the `.so`/`.dll`/`.dylib`. Relocating Java classes to `…shadow.sqlite.core.NativeDB` causes `UnsatisfiedLinkError` at first DB op. |
| **SLF4J-API** | No | Don't shade — use loader's slf4j (NeoForge ships it; Forge uses log4j2 directly) | Avoiding "shaded slf4j has no impl binding → silent no-op" trap (`forge-mod-maintenance-fork → shaded-slf4j-silent-logging.md`) |
| **MySQL connector** | No (Java 8+) | **JarInJar on every loader** | The configured backend is self-contained; the JDBC service name remains canonical |
| **Postgres JDBC** | No | **JarInJar on every loader** | Same self-contained backend strategy as MySQL |

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

## Fabric: Loom JarInJar

All four Fabric cells use Fabric Loom's supported `include modImplementation(...)`
configuration. The final remapped artifact contains the unmodified dependencies under
`META-INF/jars/`; JDBC classes and `META-INF/services/java.sql.Driver` therefore remain in
their canonical packages inside the nested jars. The Hikari include excludes the loader-owned
`org.slf4j` API.

The pinned Fabric dependency declarations are intentionally explicit because Loom 1.4.6's
Groovy dependency handler does not accept the version-catalog provider form for `include`:

```groovy
include modImplementation('org.xerial:sqlite-jdbc:3.46.1.0')
include modImplementation('com.mysql:mysql-connector-j:8.4.0')
include modImplementation('org.postgresql:postgresql:42.7.4')
include(modImplementation('com.zaxxer:HikariCP:5.1.0')) { exclude group: 'org.slf4j' }
include modImplementation('com.google.code.gson:gson:2.10.1')
```

`core` is nested using the same mechanism. Do not use Shadow output or a `-shadow`, `-all`,
or `-slim` classifier as a release asset.

## Verification Checklist (every release)

```bash
# 1. JNI symbol parity — for NeoForge/Forge (extracted from the META-INF/jarjar nested jar):
unzip -p <jar> 'META-INF/jarjar/sqlite-jdbc-*.jar' > /tmp/sqlite.jar
unzip -j /tmp/sqlite.jar 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so' -d /tmp/
nm -D /tmp/libsqlitejdbc.so | grep -E 'T Java_org_sqlite_core_NativeDB' | head -3
# MUST show Java_org_sqlite_core_NativeDB_* (not Java_network_vonix_guardian_shadow_*)

# 2. JNI symbol parity — for Fabric (extract the nested sqlite jar first):
unzip -p <fabric-jar> 'META-INF/jars/sqlite-jdbc-*.jar' > /tmp/sqlite.jar
unzip -j /tmp/sqlite.jar 'org/sqlite/native/Linux/x86_64/libsqlitejdbc.so' -d /tmp/
nm -D /tmp/libsqlitejdbc.so | grep -E 'T Java_org_sqlite_core_NativeDB' | head -3
# Same: MUST show org_sqlite_core_NativeDB

# 3. Class location parity (JDBC classes must not be outer entries):
unzip -l <jar> | grep -E 'META-INF/(jars|jarjar)/.*(sqlite|mysql|postgresql).*\.jar'
if unzip -l <jar> | grep -E '(^| )org/sqlite/|(^| )com/mysql/|(^| )org/postgresql/'; then
  echo 'JDBC classes leaked into the outer artifact' >&2; exit 1
fi
# Extract one nested jar separately when checking NativeDB.class or driver classes.

# 4. JDBC service registration:
# The service file is inside the nested driver jar, not the outer mod jar.
unzip -p <jar> 'META-INF/jars/sqlite-jdbc-*.jar' > /tmp/sqlite.jar
unzip -l /tmp/sqlite.jar | grep 'META-INF/services/java.sql.Driver'

# 5. Deterministic outer/nested check for each Fabric cell:
./gradlew -PbuildProfile=mc1182 :mc-1.18.2:fabric:verifyJarInJarPackaging
# Repeat with mc1192, mc1201, and mc1211 profiles/cells.
```

## Pitfalls

- **Don't relocate sqlite-jdbc.** It will compile clean, package clean, ship clean, and crash on first SQL op in production. Verified via `nm -D` symbol dump in this build.
- **Don't shade slf4j-api into the mod jar.** Loader provides its own; bundling yours either no-ops (no binding) or fights the loader's logging config. Use `org.apache.logging.log4j.LogManager.getLogger(...)` directly for mod-side logging.
- **JarInJar version conflicts are silent in dev environments.** ForgeGradle/NeoGradle don't extract nested jars during `runServer` because the classpath is already correct. Real-world conflicts only surface in production. Always do a clean-room verification: drop the released jar into a fresh MC server install, no IDE, and boot.
- **JarInJar metadata fingerprinting is per-version.** `sqlite-jdbc:3.46.1.0` and `sqlite-jdbc:3.50.0` are different artifacts to the loader. Pinning matters; if we bump the version, every JarInJar mod that pinned the old one loses against ours and *their* version is silently dropped. This is the loader's correct behaviour but it can hide cross-mod issues. Document the version in CHANGELOG every bump.
- **Do not relocate JDBC drivers.** Every loader keeps drivers in canonical packages inside
  its nested dependency jar, and the service registrations remain inside those jars.

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
