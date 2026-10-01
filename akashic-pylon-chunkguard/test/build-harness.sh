#!/usr/bin/env bash
# Builds the TEST-ONLY harness jar (test/out/akpg-harness.jar). Same inputs as tools/build.sh, plus FORGE_SRG and
# VANILLA_SRG: folders produced by tools/srgremap from the Crucible server.jar and server-1.7.10.jar.
set -euo pipefail
cd "$(dirname "$0")"
: "${FORGE_SRG:?}" "${VANILLA_SRG:?}" "${MODS:?}"
RT_CLASSES="${RT_CLASSES:-}"
CC_JAR=$(ls "$MODS"/ChromatiCraft*.jar | head -1)
DAPI_JAR=$(ls "$MODS"/DragonAPI*.jar | head -1)
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
javac --release 8 -proc:none -encoding UTF-8 -nowarn -cp "${RT_CLASSES:+$RT_CLASSES:}$FORGE_SRG:$VANILLA_SRG:$DAPI_JAR:$CC_JAR" \
	-d "$WORK" $(find harness/src -name '*.java')
cp harness/mcmod.info "$WORK/"
mkdir -p out && rm -f out/akpg-harness.jar && jar -c -f out/akpg-harness.jar -C "$WORK" .
echo built out/akpg-harness.jar
