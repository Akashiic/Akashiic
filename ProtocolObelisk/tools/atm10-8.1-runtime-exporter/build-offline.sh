#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <pure-atm10-8.1-runtime-root> <jdk-21-root>" >&2
  exit 2
fi

TASK_RUNTIME_ROOT=$(realpath "$1")
TASK_JDK_ROOT=$(realpath "$2")
TASK_TOOL_ROOT=$(cd -- "$(dirname -- "$0")" && pwd)
TASK_OUTPUT="$TASK_TOOL_ROOT/build/libs/protocolobelisk-atm10-8.1-runtime-exporter-1.0.0.jar"
TASK_MINECRAFT="$TASK_RUNTIME_ROOT/libraries/net/minecraft/server/1.21.1-20240808.144430/server-1.21.1-20240808.144430-srg.jar"
TASK_NEOFORGE="$TASK_RUNTIME_ROOT/libraries/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-universal.jar"
TASK_LOADER="$TASK_RUNTIME_ROOT/libraries/net/neoforged/fancymodloader/loader/4.0.44/loader-4.0.44.jar"

for TASK_REQUIRED in \
  "$TASK_JDK_ROOT/bin/javac" \
  "$TASK_JDK_ROOT/bin/jar" \
  "$TASK_MINECRAFT" \
  "$TASK_NEOFORGE" \
  "$TASK_LOADER"; do
  if [[ ! -f "$TASK_REQUIRED" ]]; then
    echo "missing exact build input: $TASK_REQUIRED" >&2
    exit 1
  fi
done

TASK_LIBRARY_CP=$(find "$TASK_RUNTIME_ROOT/libraries" -type f -name '*.jar' -print \
  | LC_ALL=C sort \
  | paste -sd: -)
TASK_COMPILE_CP="$TASK_MINECRAFT:$TASK_NEOFORGE:$TASK_LOADER:$TASK_LIBRARY_CP"
TASK_STAGING=$(mktemp -d)
trap 'find "$TASK_STAGING" -depth -delete' EXIT
mapfile -d '' TASK_SOURCES < <(
  find "$TASK_TOOL_ROOT/src/main/java" -type f -name '*.java' -print0 \
    | LC_ALL=C sort -z
)
if [[ ${#TASK_SOURCES[@]} -eq 0 ]]; then
  echo "no Java sources found" >&2
  exit 1
fi

build_once() {
  local TASK_DESTINATION=$1
  local TASK_CLASSES="$TASK_DESTINATION/classes"
  local TASK_RESOURCES="$TASK_DESTINATION/resources"
  mkdir -p "$TASK_CLASSES" "$TASK_RESOURCES"
  "$TASK_JDK_ROOT/bin/javac" \
    -proc:none \
    -Xlint:all \
    -Werror \
    --release 21 \
    -encoding UTF-8 \
    -cp "$TASK_COMPILE_CP" \
    -d "$TASK_CLASSES" \
    "${TASK_SOURCES[@]}"
  cp -R "$TASK_TOOL_ROOT/src/main/resources/." "$TASK_RESOURCES/"
  "$TASK_JDK_ROOT/bin/jar" \
    --create \
    --file "$TASK_DESTINATION/exporter.jar" \
    --date=1980-01-01T00:00:02Z \
    -C "$TASK_CLASSES" . \
    -C "$TASK_RESOURCES" .
}

build_once "$TASK_STAGING/a"
build_once "$TASK_STAGING/b"
cmp "$TASK_STAGING/a/exporter.jar" "$TASK_STAGING/b/exporter.jar"
mkdir -p "$(dirname -- "$TASK_OUTPUT")"
cp "$TASK_STAGING/a/exporter.jar" "$TASK_OUTPUT"
sha256sum "$TASK_OUTPUT"
