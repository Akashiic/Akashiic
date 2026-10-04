#!/usr/bin/env bash
# Builds dist/Akashic-Void-Sky-Fix-<version>.jar and runs VoidSkyTransformerTest.
#
#   ./build.sh <compile-classpath> <client-srg.jar> [DynamicSurroundings.jar]
#
# <compile-classpath>: ASM 5 (asm-debug-all-5.0.3), launchwrapper-1.12, the Forge 1.7.10 universal jar
#                      and the Minecraft 1.7.10 client remapped to SRG names (SpecialSource + joined.srg).
# <client-srg.jar>:    that same remapped client; the test patches its RenderGlobal and EntityRenderer.
set -euo pipefail

CP="${1:?usage: ./build.sh <compile-classpath> <client-srg.jar> [DynamicSurroundings.jar]}"
CLIENT_SRG="${2:?usage: ./build.sh <compile-classpath> <client-srg.jar> [DynamicSurroundings.jar]}"
DSURROUND="${3:-}"
cd "$(dirname "$0")"

VERSION="1.0.0"
OUT="build"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/test-classes" dist

javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$CP" -d "$OUT/classes" \
    src/main/java/com/akashic/compat/voidsky/*.java
jar cfm "dist/Akashic-Void-Sky-Fix-$VERSION.jar" src/main/resources/META-INF/MANIFEST.MF -C "$OUT/classes" .

javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$CP:$OUT/classes" -d "$OUT/test-classes" \
    src/test/java/com/akashic/compat/voidsky/*.java
java -cp "$CP:$OUT/classes:$OUT/test-classes" com.akashic.compat.voidsky.VoidSkyTransformerTest "$CLIENT_SRG" $DSURROUND

echo "Built dist/Akashic-Void-Sky-Fix-$VERSION.jar"
