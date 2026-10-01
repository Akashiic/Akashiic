#!/bin/bash
# experiment-chain.sh <name> [jvm args...]: pylon (6034,74) -> repeater (6004,82) -> charger (5986,88): the charger is out of
# 20 blocks from pylon (6034,74,2994), two chunks away; ONLY the charger's chunk is kept loaded (ticket).
# Does the charger get energy through a repeater and a pylon whose chunks are not loaded?
cd "$(dirname "$0")"
NAME=$1; shift
rm -rf world && cp -r ../world-template-chain world
./run.sh "exp-$NAME.log" -DragonAPI_disable_ASM_SPLASHPOTIONEVENT -DragonAPI_disable_ASM_CHUNKGENERATIONEVENT "$@"
./waitfor.sh "exp-$NAME.log" 'Done \(' 600
./waitfor.sh "exp-$NAME.log" 'tick=1200 ' 600          # chunk GC has run; pylon chunks are unloaded
./cmd.sh akpg ticket 5986 2994 0; sleep 1
./cmd.sh akpg charger 5986 88 2994; sleep 1
./cmd.sh akpg stats
./waitfor.sh "exp-$NAME.log" 'tick=2000 ' 600
./cmd.sh akpg chargerstat; sleep 1
./waitfor.sh "exp-$NAME.log" 'tick=2400 ' 600
./cmd.sh akpg chargerstat; sleep 1; ./cmd.sh akpg stats; sleep 2
./stop.sh
