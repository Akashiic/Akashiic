#!/bin/bash
# stop.sh: graceful stop through the console, wait for the JVM to exit (kill -9 after 180 s)
cd "$(dirname "$0")"
PID=$(cat server.pid 2>/dev/null)
{ [ -n "$PID" ] && kill -0 $PID 2>/dev/null; } || { echo "not running"; exit 0; }
echo stop > console.fifo
for i in $(seq 1 180); do kill -0 $PID 2>/dev/null || break; sleep 1; done
kill -0 $PID 2>/dev/null && { echo "forcing kill"; kill -9 $PID; sleep 2; }
echo stopped
