#!/bin/bash
# like experiment.sh, but the template world gets an empty crystalnet locs list
cd "$(dirname "$0")"
NAME=$1; SECS=$2; shift 2
rm -rf world && cp -r ../world-template world && cp ../empty-crystalnet.dat world/data/crystalnet.dat
./run.sh "exp-$NAME.log" -DragonAPI_disable_ASM_SPLASHPOTIONEVENT -DragonAPI_disable_ASM_CHUNKGENERATIONEVENT "$@"
./waitfor.sh "exp-$NAME.log" 'Done \(|Exception in thread "|This crash report|Loading cannot continue' 600 || true
sleep "$SECS"
./cmd.sh akpg stats; sleep 2
./stop.sh
