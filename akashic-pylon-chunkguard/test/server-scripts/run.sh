#!/bin/bash
# run.sh <logname> [extra jvm args...]: start the test server in background; console = ./console.fifo
# stdin is the FIFO opened read-write (<>) so the JVM never sees EOF.
cd "$(dirname "$0")"
LOG=$1; shift
rm -f console.fifo; mkfifo console.fifo
: > "$LOG"
nohup java -Xms4G -Xmx4G "$@" @java9args.txt -jar server.jar nogui 0<>console.fifo > "$LOG" 2>&1 &
echo $! > server.pid
