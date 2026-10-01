#!/usr/bin/env bash
# Builds the ProtocolObelisk capture mod against an installed, pure NeoForge 1.21.1 server.
# Usage: build-offline.sh <installed-server-root> [jdk-21-root]
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "usage: $0 <installed-neoforge-server-root> [jdk-21-root]" >&2
  exit 2
fi

RUNTIME_ROOT=$(realpath "$1")
JDK_ROOT=$(realpath "${2:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}")
TOOL_ROOT=$(cd -- "$(dirname -- "$0")" && pwd)
OUTPUT="$TOOL_ROOT/build/libs/protocolobelisk-capture-1.0.0.jar"

mapfile -t NEOFORGE_DIRS < <(find "$RUNTIME_ROOT/libraries/net/neoforged/neoforge" -mindepth 1 -maxdepth 1 -type d | LC_ALL=C sort)
if [[ ${#NEOFORGE_DIRS[@]} -ne 1 ]]; then
  echo "expected exactly one installed NeoForge version under $RUNTIME_ROOT/libraries" >&2
  exit 1
fi
NEOFORGE_VERSION=$(basename "${NEOFORGE_DIRS[0]}")
case "$NEOFORGE_VERSION" in
  21.1.*) ;;
  *) echo "unsupported NeoForge $NEOFORGE_VERSION (expected 21.1.x for Minecraft 1.21.1)" >&2; exit 1 ;;
esac

# Patched (Mojang-named) Minecraft classes and NeoForge must precede every other jar; the
# obfuscated vanilla server jar is excluded so it cannot shadow the patched classes.
PATCHED_SERVER="${NEOFORGE_DIRS[0]}/neoforge-$NEOFORGE_VERSION-server.jar"
UNIVERSAL="${NEOFORGE_DIRS[0]}/neoforge-$NEOFORGE_VERSION-universal.jar"
MOJMAP_SERVER=$(find "$RUNTIME_ROOT/libraries/net/minecraft/server" -type f -name 'server-1.21.1-*-srg.jar' -print | head -n 1)
for required in "$PATCHED_SERVER" "$UNIVERSAL" "$MOJMAP_SERVER" "$JDK_ROOT/bin/javac" "$JDK_ROOT/bin/jar"; do
  [[ -f "$required" ]] || { echo "missing build input: $required" >&2; exit 1; }
done
OTHER_CP=$(find "$RUNTIME_ROOT/libraries" -type f -name '*.jar' \
  ! -path "*/net/minecraft/server/*" ! -path "*/net/neoforged/neoforge/*" -print | LC_ALL=C sort | paste -sd: -)
LIBRARY_CP="$PATCHED_SERVER:$UNIVERSAL:$MOJMAP_SERVER:$OTHER_CP"
STAGING=$(mktemp -d)
trap 'rm -rf "$STAGING"' EXIT
mapfile -d '' SOURCES < <(find "$TOOL_ROOT/src/main/java" -type f -name '*.java' -print0 | LC_ALL=C sort -z)

build_once() {
  local destination=$1
  mkdir -p "$destination/classes" "$destination/resources"
  "$JDK_ROOT/bin/javac" -proc:none -Xlint:all -Xlint:-processing -Werror --release 21 -encoding UTF-8 \
    -cp "$LIBRARY_CP" -d "$destination/classes" "${SOURCES[@]}"
  cp -R "$TOOL_ROOT/src/main/resources/." "$destination/resources/"
  "$JDK_ROOT/bin/jar" --create --file "$destination/capture.jar" --date=1980-01-01T00:00:02Z \
    -C "$destination/classes" . -C "$destination/resources" .
}

build_once "$STAGING/a"
build_once "$STAGING/b"
cmp "$STAGING/a/capture.jar" "$STAGING/b/capture.jar"
mkdir -p "$(dirname -- "$OUTPUT")"
cp "$STAGING/a/capture.jar" "$OUTPUT"
echo "built against NeoForge $NEOFORGE_VERSION"
sha256sum "$OUTPUT"
