#!/usr/bin/env bash
set -euo pipefail
umask 022

VERSION=1.9.16-EVOLUTION

if [[ $# -ne 1 ]]; then
  echo "usage: $0 /fresh/output-directory" >&2
  echo "the Gradle 8.13 wrapper distribution and Velocity/Paper/JUnit inputs must be cached" >&2
  exit 2
fi

ROOT=$(cd "$(dirname "$0")/.." && pwd -P)
OUTPUT=$(realpath -m "$1")

if [[ "$OUTPUT" == "/" || "$OUTPUT" == "$ROOT" || "$OUTPUT" == "$(dirname "$ROOT")" ]]; then
  echo "refusing unsafe output directory: $OUTPUT" >&2
  exit 2
fi
if [[ "$OUTPUT" == "$ROOT/"* || "$ROOT" == "$OUTPUT/"* ]]; then
  echo "output directory must be disjoint from the source tree: $OUTPUT" >&2
  exit 2
fi
if [[ -e "$OUTPUT" || -L "$OUTPUT" ]]; then
  echo "output path already exists; choose a fresh dedicated directory: $OUTPUT" >&2
  exit 2
fi
OUTPUT_PARENT=$(dirname "$OUTPUT")
if [[ ! -d "$OUTPUT_PARENT" || -L "$OUTPUT_PARENT" ]]; then
  echo "output parent must be an existing non-symlink directory: $OUTPUT_PARENT" >&2
  exit 2
fi

if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_BIN=$(realpath "${JAVA_HOME}/bin/java")
elif command -v java >/dev/null 2>&1; then
  JAVA_BIN=$(realpath "$(command -v java)")
else
  echo "Java 21 was not found; set JAVA_HOME to a Java 21 JDK" >&2
  exit 2
fi
JAVA_VERSION=$($JAVA_BIN -version 2>&1 | head -n 1)
if [[ ! "$JAVA_VERSION" =~ ^(openjdk|java)[[:space:]]version[[:space:]]\"21([.]|\") ]]; then
  echo "Java 21 is required, found: $JAVA_VERSION" >&2
  exit 2
fi
JAVA_HOME=$(dirname "$(dirname "$JAVA_BIN")")
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

if [[ -n "${PYTHON:-}" ]]; then
  PYTHON_BIN=$(realpath "$PYTHON")
elif command -v python3 >/dev/null 2>&1; then
  PYTHON_BIN=$(realpath "$(command -v python3)")
else
  echo "python3 was not found; set PYTHON=/absolute/path/to/python3" >&2
  exit 2
fi
if ! "$PYTHON_BIN" -c 'import hashlib, pathlib, zipfile' >/dev/null 2>&1; then
  echo "selected Python lacks required standard-library modules: $PYTHON_BIN" >&2
  exit 2
fi

if [[ -n "${GRADLE_BIN:-}" ]]; then
  if [[ "$GRADLE_BIN" != /* || ! -f "$GRADLE_BIN" || ! -x "$GRADLE_BIN" || -L "$GRADLE_BIN" ]]; then
    echo "GRADLE_BIN must be an absolute regular executable non-symlink: $GRADLE_BIN" >&2
    exit 2
  fi
  SELECTED_GRADLE=$(realpath "$GRADLE_BIN")
elif [[ -x "$ROOT/gradlew" && ! -L "$ROOT/gradlew" ]]; then
  SELECTED_GRADLE=$ROOT/gradlew
else
  echo "Gradle wrapper is unavailable; set GRADLE_BIN=/absolute/path/to/Gradle-8.13/bin/gradle" >&2
  exit 2
fi
if [[ ! -x "$SELECTED_GRADLE" || -L "$SELECTED_GRADLE" ]]; then
  echo "Gradle launcher must be a regular executable file: $SELECTED_GRADLE" >&2
  exit 2
fi
GRADLE_VERSION=$($SELECTED_GRADLE --version | awk '/^Gradle / {print $2; exit}')
if [[ "$GRADLE_VERSION" != "8.13" ]]; then
  echo "Gradle 8.13 is required, found: ${GRADLE_VERSION:-unknown}" >&2
  exit 2
fi

export LC_ALL=C
export TZ=UTC
export PYTHONDONTWRITEBYTECODE=1

WORK=$(mktemp -d)
PUBLISH=
cleanup() {
  rm -rf -- "$WORK"
  if [[ -n "$PUBLISH" && -e "$PUBLISH" ]]; then
    rm -rf -- "$PUBLISH"
  fi
}
trap cleanup EXIT

PAPER_JAR=ProtocolObelisk-Paper-$VERSION.jar
VELOCITY_JAR=ProtocolObelisk-Velocity-$VERSION.jar

SOURCE_DIGEST_LINE=$(
  "$PYTHON_BIN" "$ROOT/tools/make-evolution-release.py" --source-sha256 "$ROOT"
)
SOURCE_DIGEST=${SOURCE_DIGEST_LINE#SOURCE_TREE_SHA256=}
if [[ ! "$SOURCE_DIGEST" =~ ^[0-9a-f]{64}$ ]]; then
  echo "could not establish canonical source-tree digest: $SOURCE_DIGEST_LINE" >&2
  exit 2
fi

verify_source_unchanged() {
  local current_line current_digest
  current_line=$(
    "$PYTHON_BIN" "$ROOT/tools/make-evolution-release.py" --source-sha256 "$ROOT"
  )
  current_digest=${current_line#SOURCE_TREE_SHA256=}
  if [[ "$current_digest" != "$SOURCE_DIGEST" ]]; then
    echo "source tree changed during independent release builds: expected $SOURCE_DIGEST, got $current_digest" >&2
    exit 1
  fi
}

GRADLE_ARGS=(
  --offline
  --no-daemon
  --no-build-cache
  --rerun-tasks
  --console=plain
  clean
  :velocity-plugin:test
  :paper-plugin:test
  :velocity-plugin:jar
  :paper-plugin:jar
)
if [[ -n "${PROTOCOL_OBELISK_OFFLINE_API_DIR:-}" ]]; then
  OFFLINE_API_DIR=$(realpath "$PROTOCOL_OBELISK_OFFLINE_API_DIR")
  if [[ ! -d "$OFFLINE_API_DIR" || -L "$OFFLINE_API_DIR" ]]; then
    echo "PROTOCOL_OBELISK_OFFLINE_API_DIR is not a regular directory: $OFFLINE_API_DIR" >&2
    exit 2
  fi
  GRADLE_ARGS=("-PofflineApiDir=$OFFLINE_API_DIR" "${GRADLE_ARGS[@]}")
fi

for run in 1 2; do
  RUN_ROOT=$WORK/run$run
  BUILD_ROOT=$RUN_ROOT/build
  JARS_ROOT=$RUN_ROOT/jars
  PUBLICATION_ROOT=$RUN_ROOT/publication
  mkdir -p "$JARS_ROOT"

  "$SELECTED_GRADLE" \
    "-PprotocolObeliskBuildRoot=$BUILD_ROOT" \
    "${GRADLE_ARGS[@]}"

  install -m 0644 \
    "$BUILD_ROOT/paper-plugin/libs/$PAPER_JAR" \
    "$JARS_ROOT/$PAPER_JAR"
  install -m 0644 \
    "$BUILD_ROOT/velocity-plugin/libs/$VELOCITY_JAR" \
    "$JARS_ROOT/$VELOCITY_JAR"
  (
    cd "$JARS_ROOT"
    sha256sum "$PAPER_JAR" "$VELOCITY_JAR" > CHECKSUMS.sha256
    chmod 0644 CHECKSUMS.sha256
  )

  "$PYTHON_BIN" "$ROOT/tools/make-evolution-release.py" \
    --validate-jars-only "$JARS_ROOT"
  "$PYTHON_BIN" "$ROOT/tools/make-evolution-release.py" \
    "$ROOT" "$JARS_ROOT" "$PUBLICATION_ROOT"
  "$PYTHON_BIN" "$ROOT/tools/make-evolution-release.py" \
    --validate-publication "$PUBLICATION_ROOT"
  verify_source_unchanged
done

"$PYTHON_BIN" - "$WORK/run1/publication" "$WORK/run2/publication" <<'PY'
from hashlib import sha256
from pathlib import Path
import sys

first = Path(sys.argv[1])
second = Path(sys.argv[2])
first_names = sorted(path.name for path in first.iterdir())
second_names = sorted(path.name for path in second.iterdir())
if first_names != second_names:
    raise SystemExit(
        f"reproducibility failure: publication trees differ: {first_names} vs {second_names}"
    )
for name in first_names:
    first_payload = (first / name).read_bytes()
    second_payload = (second / name).read_bytes()
    if first_payload != second_payload:
        raise SystemExit(
            f"reproducibility failure: independent outputs differ for {name}: "
            f"{sha256(first_payload).hexdigest()} vs {sha256(second_payload).hexdigest()}"
        )
print(f"REPRODUCIBILITY=PASS independentBuilds=2 files={len(first_names)}")
PY

PUBLISH=$WORK/run1/publication
"$PYTHON_BIN" - "$PUBLISH" "$OUTPUT" <<'PY'
import os
from pathlib import Path
import sys

source = Path(sys.argv[1])
destination = Path(sys.argv[2])
if os.path.lexists(destination):
    raise SystemExit(f"output path appeared before publication: {destination}")
for child in source.iterdir():
    descriptor = os.open(child, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
descriptor = os.open(source, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
try:
    os.fsync(descriptor)
finally:
    os.close(descriptor)
os.rename(source, destination)
parent_descriptor = os.open(destination.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
try:
    os.fsync(parent_descriptor)
finally:
    os.close(parent_descriptor)
PY
PUBLISH=

"$PYTHON_BIN" "$ROOT/tools/make-evolution-release.py" \
  --validate-publication "$OUTPUT"
echo "OFFLINE_BUILD=PASS version=$VERSION independentBuilds=2 components=Velocity,Paper"
echo "JDK java='$JAVA_VERSION'"
echo "GRADLE version='$GRADLE_VERSION' launcher='$SELECTED_GRADLE'"
sed 's/^/SHA256 /' "$OUTPUT/CHECKSUMS.sha256"
sed 's/^/RELEASE_SHA256 /' "$OUTPUT/ProtocolObelisk-$VERSION-release.zip.sha256"
echo "OUTPUT $OUTPUT"
