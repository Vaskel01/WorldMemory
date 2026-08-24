#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
BUILD="$ROOT/build/manual"
CLASSES="$BUILD/classes"
STAGE="$BUILD/stage"
OUT="$ROOT/build/libs"
CORE="$ROOT/libs/worldmemory-core-binary.jar"

rm -rf "$BUILD" "$OUT"
mkdir -p "$CLASSES" "$STAGE" "$OUT"

mapfile -t SOURCES < <(find "$ROOT/src/main/java" -type f -name '*.java' | sort)
if [[ ${#SOURCES[@]} -eq 0 ]]; then
  echo "No Java sources found." >&2
  exit 1
fi

javac --release 21 -cp "$CORE" -d "$CLASSES" "${SOURCES[@]}"
(
  cd "$STAGE"
  jar xf "$CORE"
)
cp -R "$CLASSES"/. "$STAGE"/
cp -R "$ROOT/src/main/resources"/. "$STAGE"/
rm -rf "$STAGE/META-INF"

(
  cd "$STAGE"
  jar cfm "$OUT/WorldMemory-0.1.0-alpha.53.1-reconstructed.jar" /dev/stdin . <<'MANIFEST'
Manifest-Version: 1.0
Created-By: WorldMemory reconstructed source build
MANIFEST
)

echo "Built: $OUT/WorldMemory-0.1.0-alpha.53.1-reconstructed.jar"
