# WorldMemory Narrative Studio

`/studio` is the unified navigation layer above WorldMemory's specialized authoring tools.

## Recommended workflow

1. `/studio new <type> <id>`
2. Edit the generated YAML.
3. `/studio refs <id>` and `/studio outline <id>` while wiring references.
4. `/studio validate`
5. `/studio reload`
6. `/studio play <id>` or `/studio inspect <id>`
7. Use `/cinematic`, `/scene`, `/conversation`, `/actor`, and `/wm builder` for specialized editing.

## Safety

`/studio new` never overwrites an existing ID or file.
`/studio duplicate` keeps internal references unchanged intentionally so the author can decide which references should be rewritten.
`/studio backup` snapshots dialogue, narrative, cutscene, and quest source files before large edits.
