# Building the 26.x cells

The 26.x Minecraft cells are **not** buildable with the repo's pinned Gradle
wrapper. They build fine — with the right Gradle and JDK. This document records
the measurement so nobody re-derives it by trial and error.

## Why the wrapper cannot do it

`gradle/wrapper/gradle-wrapper.properties` pins **Gradle 8.10.2**. Minecraft 26.x
NeoForge resolves a plugin whose classes are compiled for **Java 25**, which is
class-file major version **69**. Gradle 8.10.2's Groovy compiler tops out below
that, so configuration fails before any project code is read:

```
BUG! exception in phase 'semantic analysis' in source unit '_BuildScript_'
Unsupported class file major version 69
```

This is a **Gradle** limit, not a Java one. Running the 8.10.2 wrapper under JDK 25
does not help; the wrapper itself must be newer.

**Do not bump the root wrapper to 9.x to fix this.** The 1.18.2 and 1.21.1 lanes
are built and shipped on 8.10.2, and moving them has no upside. Use a separate
Gradle for the 26.x lanes instead.

## Requirements per cell

| Profile | Cell | Gradle | JDK |
|---|---|---|---|
| `coreonly` | core | 8.10.2 (wrapper) | 21 |
| `mc1182` | mc-1.18.2 forge | 8.10.2 (wrapper) | 17 |
| `mc1211` | mc-1.21.1 fabric / neoforge | 8.10.2 (wrapper) | 21 |
| `mc2612` | mc-26.1.2 neoforge | **9.2.0+** | **25** |
| `mc262` | mc-26.2 neoforge | **9.2.0+** | **25** |
| `mc263` | mc-26.3 neoforge | **9.2.0+** | **25** |
| `mc262fabric` | mc-26.2 fabric | **9.5+** | 25 |
| `mc263fabric` | mc-26.3 fabric | **9.6+** | 25 |

The Fabric rows are stricter because Fabric Loom 1.17.x requires those Gradle
versions — `settings.gradle` already keeps them in dedicated profiles for exactly
that reason.

## Measured commands

Gradle 9.2.0 installed outside the repo (so the wrapper stays pinned):

```bash
GRADLE92=/path/to/gradle-9.2.0/bin/gradle

# 26.3 NeoForge — verified, 6m44s from a cold dependency cache
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
  $GRADLE92 -PbuildProfile=mc263 :mc-26.3:neoforge:compileJava --no-daemon

# 26.1.2 / 26.2 NeoForge — same shape
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
  $GRADLE92 -PbuildProfile=mc2612 :mc-26.1.2:neoforge:compileJava --no-daemon
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
  $GRADLE92 -PbuildProfile=mc262 :mc-26.2:neoforge:compileJava --no-daemon
```

A first run of a 26.x cell spends several minutes decompiling and patching
Minecraft. That is expected and cached afterwards; it is not a hang.

## Install Gradle 9.2.0

```bash
curl -sL -o /tmp/g92.zip https://services.gradle.org/distributions/gradle-9.2.0-bin.zip
unzip -q /tmp/g92.zip -d /opt        # or any directory outside the repo
/opt/gradle-9.2.0/bin/gradle --version
```

## What this does not cover

- **Fabric** 26.2 / 26.3 were not built here. Their Gradle floors (9.5 / 9.6) are
  documented in `settings.gradle` but unmeasured in this environment.
- No 26.x cell has been **booted**. Compiling is not running: a cell can compile
  against the correct mappings and still fail at runtime against a live server.
