# Installation

## Runtime

- Java 17
- Minecraft 1.18.2
- Forge 40.3.11 installer/userdev coordinate `1.18.2-40.3.11`

Place `threaded-horizons-mc1.18.2-0.1.0-alpha.1.jar` in `mods/`. Do not install the candidate into a live production world without a backup.

## Source build

From the candidate root, with Java 17:

```shell
./gradlew clean build --no-daemon
```

Use only the reobfuscated JAR from `build/libs/`.

## First launch

1. Accept the Minecraft EULA on dedicated servers.
2. Confirm the log loads `threadedhorizons` and mixin configs `threadedhorizons.mixins.json`, `threadedhorizons-asm.mixins.json`, `threadedhorizons-compat.mixins.json`.
3. Dedicated servers do not load `threadedhorizons.client.mixins.json` client-only classes through a dedicated-server run configuration; the packaged client mixin config is still listed in the JAR manifest for client use.
4. Edit `config/threadedhorizons.toml` after the first successful start.
