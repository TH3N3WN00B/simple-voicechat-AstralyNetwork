# Simple Voice Chat - AstralyNetwork fork

Fork of [henkelmax/simple-voice-chat](https://github.com/henkelmax/simple-voice-chat) with extra group management
(owner/admins, server mute, megaphone, announce toggle) and UDP/CPU/RAM optimizations. Current version: **2.7.0-pre1.1**
(based on upstream **2.7.0-pre1**; formerly the EscoriasSMP fork).

> **Compatibility:** 2.7 uses voice chat protocol 21. Players need a 2.7 client of this fork (or upstream 2.7) to connect.
> Upstream's optional legacy layer for 2.6 clients can be enabled on the server with the JVM flag `-Dvoicechat.legacy`.

Every supported Minecraft version lives in its own folder under `versions/` with its own Gradle wrapper and toolchain
(Java 17 for 1.20.1, Java 21 for 1.21.x, Java 25 for 26.x), so all of them are built from this single `main` branch.

| Folder | Platforms |
|---|---|
| `versions/1.20.1`, `versions/1.21.1` | Fabric, Forge, (NeoForge on 1.21.1) |
| `versions/1.21.4` .. `versions/1.21.11` | Fabric, Forge, NeoForge, Paper |
| `versions/26.1` | Fabric, Forge, NeoForge, Paper |
| `versions/26.2`, `versions/26.3` | Fabric, Forge, NeoForge, Paper, Bukkit, BungeeCord, Velocity (4.3.0) |

The group management GUI exists on 1.21.10 and newer. Older versions use the `/voicechat` commands.

## Build

```bash
./build-all.sh            # all versions, jars end up in dist/<version>/
./build-all.sh 1.21.4     # a single version
```

On Windows use `.\build-all.ps1` (optionally `.\build-all.ps1 -Versions 1.21.4,26.2`). JDK 17, 21 and 25 are needed
(Gradle toolchains provision missing ones). Or build one version directly: `cd versions/<version> && ./gradlew build`.

Velocity does not depend on the Minecraft version: use the single jar from `versions/26.3` (or `26.2`).
