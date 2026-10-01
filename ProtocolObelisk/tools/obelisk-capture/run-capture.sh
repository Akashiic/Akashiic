#!/usr/bin/env bash
# ProtocolObelisk universal capture runner.
#
# Boots a disposable, pure NeoForge copy of a modpack's official ServerFiles with the build-only
# capture mod, twice from scratch, requires both captures to agree (byte-identical, or identical
# up to the orderings a real server leaves nondeterministic) and packages the result as a
# compatibility pack (.obpack) that ProtocolObelisk loads at runtime.
#
# Usage:
#   run-capture.sh --server-files ServerFiles-X.zip --pack-id atm10-8.2 --work /abs/workdir \
#                  --out /abs/atm10-8.2.obpack --accept-eula [--display-name "ATM10 8.2"] \
#                  [--source-url URL] [--boots 2] [--xmx 10G] [--timeout-minutes 45]
#
# --accept-eula states that you accept the Minecraft EULA (https://aka.ms/MinecraftEULA) for this
# disposable capture server. Nothing here touches a production server, client or world.
set -euo pipefail

SERVER_FILES=""
PACK_ID=""
WORK=""
OUT=""
DISPLAY_NAME=""
SOURCE_URL=""
BOOTS=2
XMX="10G"
TIMEOUT_MINUTES=45
ACCEPT_EULA=false
TOOL_ROOT=$(cd -- "$(dirname -- "$0")" && pwd)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-files) SERVER_FILES=$(realpath "$2"); shift 2 ;;
    --pack-id) PACK_ID="$2"; shift 2 ;;
    --work) WORK=$(realpath -m "$2"); shift 2 ;;
    --out) OUT=$(realpath -m "$2"); shift 2 ;;
    --display-name) DISPLAY_NAME="$2"; shift 2 ;;
    --source-url) SOURCE_URL="$2"; shift 2 ;;
    --boots) BOOTS="$2"; shift 2 ;;
    --xmx) XMX="$2"; shift 2 ;;
    --timeout-minutes) TIMEOUT_MINUTES="$2"; shift 2 ;;
    --accept-eula) ACCEPT_EULA=true; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -f "$SERVER_FILES" ]] || { echo "--server-files must name an existing ServerFiles zip" >&2; exit 2; }
[[ "$PACK_ID" =~ ^[a-z0-9][a-z0-9._-]{0,63}$ ]] || { echo "--pack-id must match [a-z0-9][a-z0-9._-]{0,63}" >&2; exit 2; }
[[ -n "$WORK" && -n "$OUT" ]] || { echo "--work and --out are required" >&2; exit 2; }
[[ "$BOOTS" =~ ^[1-9]$ ]] || { echo "--boots must be 1..9" >&2; exit 2; }
[[ "$ACCEPT_EULA" == true ]] || { echo "pass --accept-eula to accept the Minecraft EULA for the disposable capture server" >&2; exit 2; }
[[ -e "$OUT" ]] && { echo "refusing to overwrite $OUT" >&2; exit 2; }
DISPLAY_NAME=${DISPLAY_NAME:-$PACK_ID}
command -v java >/dev/null || { echo "Java 21 is required" >&2; exit 2; }

mkdir -p "$WORK"
BASE="$WORK/base"
log() { printf '[obelisk-capture] %s\n' "$*"; }

if [[ ! -d "$BASE/libraries" ]]; then
  log "extracting $(basename "$SERVER_FILES") into $BASE"
  rm -rf "$BASE"
  mkdir -p "$BASE"
  (cd "$BASE" && unzip -q "$SERVER_FILES")
  mapfile -t INSTALLERS < <(find "$BASE" -maxdepth 1 -name 'neoforge-*-installer.jar' | LC_ALL=C sort)
  if [[ ${#INSTALLERS[@]} -ne 1 ]]; then
    VERSION=$(sed -n 's/^NEOFORGE_VERSION=//p' "$BASE/startserver.sh" 2>/dev/null | head -n 1)
    [[ -n "$VERSION" ]] || { echo "cannot determine the NeoForge version of these ServerFiles" >&2; exit 1; }
    log "downloading NeoForge $VERSION installer"
    curl -fsSL -o "$BASE/neoforge-$VERSION-installer.jar" \
      "https://maven.neoforged.net/releases/net/neoforged/neoforge/$VERSION/neoforge-$VERSION-installer.jar"
    INSTALLERS=("$BASE/neoforge-$VERSION-installer.jar")
  fi
  log "installing $(basename "${INSTALLERS[0]}")"
  (cd "$BASE" && java -jar "${INSTALLERS[0]}" --installServer > installer.log 2>&1) \
    || { echo "NeoForge installer failed; see $BASE/installer.log" >&2; exit 1; }
fi

NEOFORGE_VERSION=$(basename "$(find "$BASE/libraries/net/neoforged/neoforge" -mindepth 1 -maxdepth 1 -type d | head -n 1)")
log "NeoForge $NEOFORGE_VERSION"
"$TOOL_ROOT/build-offline.sh" "$BASE" > "$WORK/capture-build.log" 2>&1 \
  || { cat "$WORK/capture-build.log" >&2; exit 1; }
CAPTURE_JAR="$TOOL_ROOT/build/libs/protocolobelisk-capture-1.0.0.jar"

for ((boot = 1; boot <= BOOTS; boot++)); do
  RUN="$WORK/boot-$boot"
  EXPORT="$WORK/capture-$boot"
  rm -rf "$RUN" "$EXPORT"
  log "boot $boot/$BOOTS: preparing fresh runtime"
  cp -a "$BASE" "$RUN"
  cp "$CAPTURE_JAR" "$RUN/mods/"
  printf 'eula=true\n' > "$RUN/eula.txt"
  PORT=$((40000 + RANDOM % 20000))
  cat > "$RUN/server.properties" <<PROPS
level-name=obelisk-capture
level-type=minecraft\:flat
generate-structures=false
online-mode=false
server-port=$PORT
enable-rcon=false
enable-query=false
spawn-protection=0
max-tick-time=-1
view-distance=2
simulation-distance=2
PROPS
  log "boot $boot/$BOOTS: starting (timeout ${TIMEOUT_MINUTES}m, Xmx $XMX)"
  set +e
  (cd "$RUN" && timeout "${TIMEOUT_MINUTES}m" java "-Xms2G" "-Xmx$XMX" -XX:+UseG1GC \
      "-Dprotocolobelisk.capture.output=$EXPORT" \
      "-Dprotocolobelisk.capture.packId=$PACK_ID" \
      "-Dprotocolobelisk.capture.displayName=$DISPLAY_NAME" \
      "-Dprotocolobelisk.capture.serverFiles=$SERVER_FILES" \
      "-Dprotocolobelisk.capture.sourceUrl=$SOURCE_URL" \
      "@libraries/net/neoforged/neoforge/$NEOFORGE_VERSION/unix_args.txt" nogui \
      < /dev/null > "$WORK/boot-$boot.log" 2>&1)
  STATUS=$?
  set -e
  if [[ ! -d "$EXPORT" ]]; then
    echo "boot $boot produced no capture (exit $STATUS); see $WORK/boot-$boot.log" >&2
    grep -n "ProtocolObelisk capture" "$WORK/boot-$boot.log" >&2 || true
    exit 1
  fi
  grep "ProtocolObelisk capture" "$WORK/boot-$boot.log" || true
  rm -rf "$RUN"
done

if [[ "$BOOTS" -gt 1 ]]; then
  for ((boot = 2; boot <= BOOTS; boot++)); do
    python3 "$TOOL_ROOT/obpack.py" compare "$WORK/capture-1" "$WORK/capture-$boot"
  done
fi
python3 "$TOOL_ROOT/obpack.py" pack "$WORK/capture-1" "$OUT" --boots "$BOOTS"
python3 "$TOOL_ROOT/obpack.py" inspect "$OUT"
