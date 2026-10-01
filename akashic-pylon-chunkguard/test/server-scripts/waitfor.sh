#!/bin/bash
# usage: waitfor.sh <log> <regex> <timeout_s>
cd "$(dirname "$0")"
for i in $(seq 1 $3); do if grep -qE "$2" "$1"; then exit 0; fi; if ! kill -0 $(cat server.pid) 2>/dev/null; then echo "server died"; exit 2; fi; sleep 1; done; echo timeout; exit 1
