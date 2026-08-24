# WorldMemory

WorldMemory is a Paper-based narrative action-adventure game engine for Minecraft. The current reconstructed repository corresponds to `0.1.0-alpha.53.1` and includes the Narrative Core V1, quests, animations, directed scenes, cinematic tooling, actors and relationships, co-op reliability, crash recovery, content validation, world-builder tools and diagnostics.

> **Source reconstruction notice:** the complete original pre-alpha.36 Java source tree is no longer available. This repository is a buildable reconstruction: the recovered narrative-layer Java sources are included normally, while older unrecovered engine classes are preserved in a stripped binary core. See [SOURCE-RECOVERY.md](SOURCE-RECOVERY.md).

## Requirements

- Java / JDK 21
- Paper 26.2 server for deployment
- WorldEdit, WorldGuard and Citizens are supported integrations; other integrations are optional according to `plugin.yml`.

## Build

### Windows PowerShell

```powershell
./build.ps1
```

### Linux / macOS

```bash
./build.sh
```

Output:

```text
build/libs/WorldMemory-0.1.0-alpha.53.1-reconstructed.jar
```

A Gradle build file is also provided for IDE/project import.

## Repository layout

```text
src/main/java/       Recovered Java source
src/main/resources/  plugin.yml, config, content pack and database migration
libs/                 Stripped compiled engine core required by reconstruction
docs/                 Development/pass notes and narrative authoring docs
reference/            Exact alpha.53.1 release JAR for comparison
.github/workflows/    CI build
```

## Major systems

- Instance lifecycle and Recall
- Memory Ash and Ash Anchors
- Remnants
- Generic animation engine and legacy gates
- Quest and progression runtime
- Convergence/co-op sessions
- Content integrity and safe reloads
- Crash recovery
- Performance instrumentation
- World-builder/admin tools
- Narrative stories, dialogue and choices
- Actor relationships, flags, emotion and pose state
- Conversation flow, imports, queues and interruption/resume
- Cinematics and timeline authoring
- Multi-character directed scenes
- Narrative Studio
- Narrative Guard/hardening

The full feature documentation is maintained in the separate GitHub Wiki export created for alpha.53.1.

## Important

Do not delete `libs/worldmemory-core-binary.jar` until the unrecovered engine source has been recreated. The reconstructed Java sources compile against it and the build merges both halves into the final plugin JAR.
