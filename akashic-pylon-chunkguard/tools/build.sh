#!/usr/bin/env bash
# Builds dist/akashic-pylon-chunkguard-<version>.jar.
#
# No Gradle/ForgeGradle and no hand-written stubs. javac compiles against:
#   1. RT_CLASSES (optional, recommended): Minecraft classes exactly as the Crucible server defines them at runtime
#      (SRG names, Forge binpatches and coremod transforms applied). Produce it by starting the server once with
#      -Dlegacy.debugClassLoadingSave=true and pointing RT_CLASSES at <server>/RFB_CLASS_DUMP/Launch.
#   2. The Forge/FML classes of the Crucible server.jar and the vanilla server-1.7.10.jar, remapped notch->SRG with
#      FML's own deobfuscation table (deobfuscation_data-1.7.10.lzma, shipped inside server.jar) by tools/srgremap.
#   3. The real ChromatiCraft, DragonAPI and UniMixins jars of the server (MODS folder).
# The sources use SRG names directly, so the class files need no reobfuscation step.
#
# Requirements: JDK >= 17 (javac --release 8, jar --date), python3, unzip.
# usage: CRUCIBLE=<server.jar> VANILLA=<server-1.7.10.jar> ASM_DIR=<libraries/org/ow2/asm> MODS=<mods dir> \
#        [RT_CLASSES=<RFB_CLASS_DUMP/Launch>] tools/build.sh [version]
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="${1:-1.0.0}"
: "${CRUCIBLE:?set CRUCIBLE}" "${VANILLA:?set VANILLA}" "${ASM_DIR:?set ASM_DIR}" "${MODS:?set MODS}"
RT_CLASSES="${RT_CLASSES:-}"

CC_JAR=$(ls "$MODS"/ChromatiCraft*.jar | head -1)
DAPI_JAR=$(ls "$MODS"/DragonAPI*.jar | head -1)
UNIMIX_JAR=$(ls "$MODS"/*unimixins*.jar | head -1)
ASM_CP=$(ls "$ASM_DIR"/asm/*/asm-*.jar "$ASM_DIR"/asm-commons/*/asm-commons-*.jar "$ASM_DIR"/asm-tree/*/asm-tree-*.jar | tr '\n' ':')

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/remap-tool" "$WORK/forge" "$WORK/vanilla" "$WORK/classes" dist

# FML deobfuscation table (LZMA "alone" format) -> plain .srg
unzip -q -o -j "$CRUCIBLE" deobfuscation_data-1.7.10.lzma -d "$WORK"
python3 -c "import lzma,sys; open(sys.argv[2],'wb').write(lzma.open(sys.argv[1],format=lzma.FORMAT_ALONE).read())" \
	"$WORK/deobfuscation_data-1.7.10.lzma" "$WORK/deobf.srg"

javac -nowarn -cp "$ASM_CP" -d "$WORK/remap-tool" tools/srgremap/src/srgremap/SrgRemap.java
java -cp "$ASM_CP$WORK/remap-tool" srgremap.SrgRemap "$WORK/deobf.srg" "$CRUCIBLE" "$WORK/forge" cpw/ net/minecraftforge/
java -cp "$ASM_CP$WORK/remap-tool" srgremap.SrgRemap "$WORK/deobf.srg" "$VANILLA" "$WORK/vanilla"

CP="${RT_CLASSES:+$RT_CLASSES:}$WORK/forge:$WORK/vanilla:$DAPI_JAR:$CC_JAR:$UNIMIX_JAR"
find src/main/java -name '*.java' | sort > "$WORK/sources.txt"
javac --release 8 -proc:none -encoding UTF-8 -Xlint:all,-options,-processing,-path -Werror \
	-cp "$CP" -d "$WORK/classes" @"$WORK/sources.txt"
cp -r src/main/resources/. "$WORK/classes/"
sed -i "s/\"version\": \"[^\"]*\"/\"version\": \"$VERSION\"/" "$WORK/classes/mcmod.info"

OUT="dist/akashic-pylon-chunkguard-$VERSION.jar"
rm -f "$OUT"
# fixed timestamp => byte-identical jar for identical inputs
jar --date=2026-10-01T00:00:00Z -c -f "$OUT" -C "$WORK/classes" .
(cd dist && sha256sum "$(basename "$OUT")" | tee "$(basename "$OUT").sha256")
