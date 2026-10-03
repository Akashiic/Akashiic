#!/usr/bin/env bash
# Builds dist/AkashicVoidGenerator-<version>.jar and runs the CustomChunkGenerator simulation.
#
#   ./build.sh <bukkit-api-classpath> [legacy-VoidGenerator.jar]
#
# <bukkit-api-classpath> must provide the Bukkit 1.7.10 API, e.g. the Crucible server jar (its
# manifest Class-Path pulls in Guava & co. from libraries/). Quote it if it contains '*'.
# Passing the old VoidGenerator.jar also reproduces its crash in the simulation.
set -euo pipefail

API_CP="${1:?usage: ./build.sh <bukkit-api-classpath> [legacy-VoidGenerator.jar]}"
LEGACY_JAR="${2:-}"
cd "$(dirname "$0")"

VERSION="$(sed -n 's/^version: *//p' src/main/resources/plugin.yml)"
OUT="build"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/test-classes" dist

javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$API_CP" -d "$OUT/classes" \
    src/main/java/br/com/redeakashic/voidgen/*.java
cp src/main/resources/plugin.yml "$OUT/classes/"
jar cf "dist/AkashicVoidGenerator-$VERSION.jar" -C "$OUT/classes" .

javac --release 8 -Xlint:-options -encoding UTF-8 -cp "$API_CP:$OUT/classes" -d "$OUT/test-classes" \
    src/test/java/br/com/redeakashic/voidgen/*.java
if [ -n "$LEGACY_JAR" ]; then
    java -cp "$API_CP:$OUT/classes:$OUT/test-classes" br.com.redeakashic.voidgen.CustomChunkGeneratorSimulation \
        "$LEGACY_JAR" me.Bogdacutu.VoidGenerator.VoidGeneratorGenerator
else
    java -cp "$API_CP:$OUT/classes:$OUT/test-classes" br.com.redeakashic.voidgen.CustomChunkGeneratorSimulation
fi

echo "Built dist/AkashicVoidGenerator-$VERSION.jar"
