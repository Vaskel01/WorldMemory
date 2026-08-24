# Source Recovery Status

This repository was reconstructed from the working `WorldMemory 0.1.0-alpha.53.1` plugin JAR.

The original complete Java source tree used before the alpha.36 binary-patch series is not available in the recovered project history. This repository therefore separates code into two categories.

## Recovered Java source

The following current narrative-layer classes are available as Java source under `src/main/java`:

- `NarrativeActors`
- `NarrativeBridge`
- `NarrativeComposition`
- `NarrativeDirector`
- `NarrativeFlow`
- `NarrativeHardening`
- `NarrativePresentation`
- `NarrativeScenes`
- `NarrativeSessions`
- `NarrativeStudio`

These sources correspond to the narrative passes developed from alpha.46 through alpha.53.

## Binary-preserved engine core

Older engine classes for animation, instances, persistence, Ash, anchors, quests, builders, commands, listeners, co-op, diagnostics, recovery and `NarrativeCore` remain compiled in:

`libs/worldmemory-core-binary.jar`

The binary core has plugin resources and the recovered narrative classes removed. The build scripts combine it with the recovered Java source and `src/main/resources`.

This means the repository is **buildable and editable**, but it is **not yet a 100% source-complete open-source reconstruction**.

## Exact release reference

`reference/WorldMemory-0.1.0-alpha.53.1.jar` is the exact known-good release used for reconstruction. It is included only as a reference artifact and is not used by the build.

## Long-term cleanup

The ideal next repository-maintenance task is to replace classes from `libs/worldmemory-core-binary.jar` with clean Java implementations one subsystem at a time. Once every preserved class has source, the binary core can be deleted entirely.
