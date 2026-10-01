#!/bin/bash
cd "$(dirname "$0")"
export NBTLOCS="${NBTLOCS:?set NBTLOCS=<repo>/akashic-pylon-chunkguard/test/tools/nbtlocs.py}"
PG=../pg-off/akashic-pylon-chunkguard-1.0.1.jar
rm -f mods/akashic-pylon-chunkguard-*.jar; cp $PG mods/
# R1/R2: two consecutive boots with 1.0.1, R3: rollback (jar removed)
./experiment.sh R1-boot1 120 > R1.out 2>&1
./experiment-keep.sh R2-boot2 120 > R2.out 2>&1
mv mods/akashic-pylon-chunkguard-1.0.1.jar ../pg-off/tmp.jar
./experiment-keep.sh R3-rollback 120 > R3.out 2>&1
mv ../pg-off/tmp.jar mods/akashic-pylon-chunkguard-1.0.1.jar
# R4: empty network list (first registered tile must survive)
./experiment-emptynet.sh R4-emptynet 40 > R4.out 2>&1
# R5: old lazyload jar present as well
cp ../zip/akashic-crystalnet-lazyload-1.0.1.jar mods/
./experiment.sh R5-bothjars 90 > R5.out 2>&1
rm mods/akashic-crystalnet-lazyload-1.0.1.jar
echo REGRESSION-DONE > regression101.done
