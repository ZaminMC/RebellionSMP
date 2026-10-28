#!/usr/bin/env bash
# Headless Paper 26.3 boot-smoke for the RebellionSMP plugin family
# (ADR-0011). Boots the real Paper server with the built module JARs on a
# flat offline-mode world and exercises the console admin surface.
#
# This is the boot-level verification gate: install order, first-boot config
# generation, schema migrations, console commands, restart persistence and
# graceful shutdown. Player-level flows (menus, teams invites, ward walking)
# still need interactive verification - see docs/TEST_PLAN.md.
#
# Requirements: JDK 25 (JAVA_BIN or on PATH), network for the PaperMC fill
# API download, ~2 GB free RAM, ~1 GB disk.
#
# Usage:
#   PAPER_BUILD=226 ./scripts/boot-smoke.sh fetch     # download server jar (checksum-verified)
#   ./scripts/boot-smoke.sh jars                      # copy built module jars into plugins/
#   ./scripts/boot-smoke.sh start                     # boot (first run generates the world)
#   ./scripts/boot-smoke.sh send 'rebellion status'   # console command
#   ./scripts/boot-smoke.sh stop                      # graceful stop
#
# Environment overrides:
#   JAVA_BIN       java 25 binary            (default: java from PATH)
#   SERVER_DIR     scratch server directory  (default: build/paper-smoke)
#   PAPER_BUILD    Paper 26.3 build number   (default: 226, the pinned line)
#   MC_VERSION     Minecraft version         (default: 26.3)
set -u

JAVA_BIN="${JAVA_BIN:-java}"
SERVER_DIR="${SERVER_DIR:-build/paper-smoke}"
MC_VERSION="${MC_VERSION:-26.3}"
PAPER_BUILD="${PAPER_BUILD:-226}"
JAR="paper-$MC_VERSION-$PAPER_BUILD.jar"
FILL="https://fill.papermc.io/v3/projects/paper/versions/$MC_VERSION/builds/$PAPER_BUILD"

command -v "$JAVA_BIN" >/dev/null || { echo "java not found (set JAVA_BIN)" >&2; exit 2; }

# The module jars that ship plugin.yml, in install order (master prompt §17).
# Updated with each new installable module: dragons (session 10),
# artifacts (session 11), rpg (session 12/14 — ADR-0017 controller slice),
# alchemy (session 16 — ADR-0020 controller slice).
MODULE_DIRS=(
  "rebellion-api/build/libs"
  "rebellion-core/build/libs"
  "rebellion-items/build/libs"
  "rebellion-teams/build/libs"
  "rebellion-wards/build/libs"
  "rebellion-dragons/build/libs"
  "rebellion-artifacts/build/libs"
  "rebellion-rpg/build/libs"
  "rebellion-alchemy/build/libs"
)

case "${1:-}" in
  fetch)
    mkdir -p "$SERVER_DIR"
    META=$(curl -fsSL --max-time 30 "$FILL") || { echo "fill API unreachable" >&2; exit 1; }
    # No jq dependency: single python3 extraction for portability.
    read -r URL SHA SIZE <<<"$(printf '%s' "$META" | python3 -c \
      "import json,sys; d=json.load(sys.stdin)['downloads']['server:default']; print(d['url'], d['checksums']['sha256'], d['size'])")"
    curl -fsSL --max-time 600 "$URL" -o "$SERVER_DIR/$JAR"
    echo "$SHA  $SERVER_DIR/$JAR" | sha256sum -c - || { echo "checksum mismatch" >&2; exit 1; }
    echo "fetched $JAR ($SIZE bytes, sha256 verified)"
    ;;
  jars)
    VERSION=$(sed -n 's/^version=//p' gradle.properties)
    mkdir -p "$SERVER_DIR/plugins"
    rm -f "$SERVER_DIR/plugins/Rebellion"*.jar
    for dir in "${MODULE_DIRS[@]}"; do
      name=$(ls "$dir" | grep -E '^Rebellion.*-'"$VERSION"'\.jar$' | grep -v -- '-thin' | head -1)
      [ -n "$name" ] || { echo "missing installable jar in $dir (run ./gradlew build)" >&2; exit 1; }
      cp "$dir/$name" "$SERVER_DIR/plugins/"
    done
    ls -la "$SERVER_DIR/plugins/"
    ;;
  start)
    cd "$SERVER_DIR"
    printf 'eula=true\n' > eula.txt
    # Flat offline world keeps boot fast and reproducible; the smoke tests
    # plugin lifecycle, not terrain.
    [ -f server.properties ] || cat > server.properties << 'EOF'
online-mode=false
level-type=minecraft\:flat
generate-structures=false
view-distance=4
simulation-distance=4
spawn-protection=0
max-players=5
motd=RebellionSMP boot smoke
white-list=false
EOF
    [ -p console.in ] || mkfifo console.in
    # tail -f keeps the FIFO open so later writers never block/EOF the server.
    tail -f console.in | "$JAVA_BIN" -Xms512M -Xmx1536M -jar "$JAR" --nogui > server.log 2>&1 &
    echo $! > server.pid
    echo "server starting (pid $(cat server.pid)); tail -f $SERVER_DIR/server.log"
    ;;
  send)
    [ -p "$SERVER_DIR/console.in" ] || { echo "server not started" >&2; exit 2; }
    echo "${2:?}" > "$SERVER_DIR/console.in"
    ;;
  stop)
    [ -p "$SERVER_DIR/console.in" ] || { echo "server not started" >&2; exit 2; }
    echo "stop" > "$SERVER_DIR/console.in"
    PID=$(cat "$SERVER_DIR/server.pid")
    for _ in $(seq 1 60); do
      kill -0 "$PID" 2>/dev/null || { echo "server exited cleanly"; exit 0; }
      sleep 2
    done
    echo "server still alive after 120s; check $SERVER_DIR/server.log" >&2
    exit 1
    ;;
  *)
    echo "usage: $0 fetch|jars|start|send '<cmd>'|stop" >&2
    echo "  (run from the repository root; SERVER_DIR/ JAVA_BIN overridable)" >&2
    exit 2
    ;;
esac
