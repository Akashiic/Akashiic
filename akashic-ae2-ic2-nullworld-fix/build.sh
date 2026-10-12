#!/usr/bin/env bash
# Builds dist/Akashic-AE2-IC2-NullWorldFix-<version>-serveronly.jar and runs the tests.
# Needs a JDK 9+ (javac --release 8). Dependencies are downloaded into libs/ and checked by SHA-256.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=1.0.0
JAR="dist/Akashic-AE2-IC2-NullWorldFix-${VERSION}-serveronly.jar"
LIBS=libs
BUILD=build

fetch() { # name url sha256
    local out="$LIBS/$1"
    if [ ! -f "$out" ]; then
        for i in 1 2 3 4; do curl -sSfL -o "$out.part" "$2" && break; sleep $((i * 4)); done
        mv "$out.part" "$out"
    fi
    echo "$3  $out" | sha256sum -c --quiet -
}

mkdir -p "$LIBS" dist
fetch forge-universal.jar https://maven.minecraftforge.net/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar 00d1ca02192c7efb87da95552ebf247021f1e1d642f86d0a03076314def11529
fetch launchwrapper-1.12.jar https://libraries.minecraft.net/net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar 57f402b626d16cc2705bf2a37add7adbb074f0ca3b3102fa6e23aa303dae682f
fetch asm-all-5.0.3.jar https://repo1.maven.org/maven2/org/ow2/asm/asm-all/5.0.3/asm-all-5.0.3.jar 09d44e6adddaab9fd89ea3164c439f71c755769fa8d3a940352ab3d2003b98ff
fetch guava-17.0.jar https://repo1.maven.org/maven2/com/google/guava/guava/17.0/guava-17.0.jar 8c36a80ea613d0b6b8040a17cf837c5bbe3677bc1b06a058a6c174fdb787ebbc
fetch log4j-api-2.0-beta9.jar https://repo1.maven.org/maven2/org/apache/logging/log4j/log4j-api/2.0-beta9/log4j-api-2.0-beta9.jar eb0ca9bdd938f80711152a758d6bc331a8b0d769199d457294dc67ed03f73498
fetch ae2-rv3-beta-1035-GTNH.jar https://github.com/GTNewHorizons/Applied-Energistics-2-Unofficial/releases/download/rv3-beta-1035-GTNH/appliedenergistics2-rv3-beta-1035-GTNH.jar c1e126473c771cd12d4d352123cbeff180bd338a754ee1cd739cea1942a63d23

CP="$LIBS/forge-universal.jar:$LIBS/launchwrapper-1.12.jar:$LIBS/asm-all-5.0.3.jar:$LIBS/guava-17.0.jar:$LIBS/log4j-api-2.0-beta9.jar"
rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/stubs" "$BUILD/ic2only" "$BUILD/test"

echo "== compile mod (Java 8 bytecode)"
javac --release 8 -nowarn -encoding UTF-8 -cp "$CP" -d "$BUILD/classes" $(find src/main/java -name '*.java')
rm -f "$JAR"
jar --create --file "$JAR" --manifest src/main/resources/META-INF/MANIFEST.MF -C "$BUILD/classes" .

echo "== compile tests"
(cd "$BUILD/ic2only" && unzip -q "../../$LIBS/ae2-rv3-beta-1035-GTNH.jar" 'appeng/tile/powersink/IC2.class')
javac --release 8 -nowarn -encoding UTF-8 -cp "$BUILD/ic2only:$LIBS/forge-universal.jar" -d "$BUILD/stubs" $(find src/test/stubs -name '*.java')
rm -rf "$BUILD/stubs/appeng/tile/powersink/IC2.class"
javac --release 8 -nowarn -encoding UTF-8 -cp "$BUILD/classes:$CP" -d "$BUILD/test" $(find src/test/java -name '*.java')

echo "== run tests"
java -cp "$BUILD/test:$BUILD/classes:$CP" br.com.redeakashic.ae2ic2fix.NullWorldGuardTest \
    "$LIBS/ae2-rv3-beta-1035-GTNH.jar" "$BUILD/stubs" "$BUILD/ic2only" "$JAR" \
    "$LIBS/asm-all-5.0.3.jar" "$LIBS/forge-universal.jar"

echo "== built $JAR"
sha256sum "$JAR"
