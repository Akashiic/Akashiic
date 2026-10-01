#!/usr/bin/env bash
# Build-only platform descriptors. Never install or package the resulting JAR.
set -euo pipefail
umask 022

if [[ $# -ne 2 ]]; then
  echo "usage: $0 /path/to/java21-jdk /path/to/offline-api-inputs" >&2
  exit 2
fi
SOURCE_ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
BUILD_JDK=$(realpath "$1")
API_INPUTS=$(realpath "$2")
if [[ ! -d "$BUILD_JDK" || ! -x "$BUILD_JDK/bin/javac" || ! -x "$BUILD_JDK/bin/jar" ]]; then
  echo "a complete Java21 JDK is required" >&2
  exit 2
fi
if [[ ! -d "$API_INPUTS" || -L "$API_INPUTS" ]]; then
  echo "offline-api-inputs must be an existing non-symlink directory" >&2
  exit 2
fi
API_OUTPUT="$API_INPUTS/obelisk-compile-only-api.jar"
if [[ -e "$API_OUTPUT" || -L "$API_OUTPUT" ]]; then
  echo "refusing to replace an existing compile-only API artifact: $API_OUTPUT" >&2
  exit 2
fi
JAVAC_VERSION=$("$BUILD_JDK/bin/javac" -version)
if [[ "$JAVAC_VERSION" != javac\ 21.* ]]; then
  echo "Java21 is required, got $JAVAC_VERSION" >&2
  exit 2
fi
NETTY_CLASSPATH=
for component in common buffer transport resolver; do
  dependency="$API_INPUTS/netty-$component-4.1.97.Final.jar"
  if [[ ! -f "$dependency" || -L "$dependency" ]]; then
    echo "missing regular real Netty input: $dependency" >&2
    exit 2
  fi
  NETTY_CLASSPATH="${NETTY_CLASSPATH:+$NETTY_CLASSPATH:}$dependency"
done

# Both descriptor trees provide Adventure classes. The Paper tree is the
# superset (including Component.text(String)); use it exactly once. Exclude
# every io/netty descriptor, since compile/test must use the real Netty JARs.
mapfile -t API_SOURCES < <(rg --files \
  "$SOURCE_ROOT/tools/offline-velocity-api-stubs" \
  "$SOURCE_ROOT/tools/offline-paper-api-stubs" -g '*.java' \
  | LC_ALL=C sort \
  | sed '\|/io/netty/|d;\|/offline-velocity-api-stubs/net/kyori/|d')
if [[ ${#API_SOURCES[@]} -eq 0 ]]; then
  echo "compile-only descriptor sources were not found" >&2
  exit 2
fi
API_CLASSES=$(mktemp -d)
"$BUILD_JDK/bin/javac" --release 21 -encoding UTF-8 \
  -classpath "$NETTY_CLASSPATH" -d "$API_CLASSES" "${API_SOURCES[@]}"
"$BUILD_JDK/bin/jar" --create --file "$API_OUTPUT" \
  --date=2026-09-04T00:00:00Z -C "$API_CLASSES" .
echo "API_SOURCE_COUNT=${#API_SOURCES[@]}"
echo "API_CLASSES_PATH=$API_CLASSES"
sha256sum "$API_OUTPUT"
