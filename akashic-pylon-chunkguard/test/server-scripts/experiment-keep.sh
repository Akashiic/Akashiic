#!/bin/bash
# like experiment.sh but keeps the current world (consecutive boots)
cd "$(dirname "$0")"
NAME=$1; SECS=$2; shift 2
./run.sh "exp-$NAME.log" -DragonAPI_disable_ASM_SPLASHPOTIONEVENT -DragonAPI_disable_ASM_CHUNKGENERATIONEVENT "$@"
./waitfor.sh "exp-$NAME.log" 'Done \(|Exception in thread "|This crash report|Loading cannot continue' 600 || true
sleep "$SECS"
./cmd.sh akpg stats; sleep 2
./stop.sh
python3 "${NBTLOCS:?set NBTLOCS=<repo>/akashic-pylon-chunkguard/test/tools/nbtlocs.py}" world/data/crystalnet.dat 0 2>/dev/null | sed "s/^/[after-stop crystalnet.dat] /"
