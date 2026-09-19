#!/usr/bin/env bash
#
# What the MPRIS paths cost, measured rather than reasoned about.
#
# Not part of the build, like the oracles beside it. Run it by hand before and
# after touching anything in libsound-dbus or libsound-session, because the
# numbers it prints are the ones a desktop feels and none of them is visible in
# a passing test: a session that answers in 100 ms and one that answers in 1 ms
# are both correct and only one of them is usable.
#
# It was written for a specific finding. The I/O loop waited on a clock rather
# than on the bus, so everything a caller queued sat out the rest of the
# interval: a published signal took 54 ms on average and a read of three players
# took 956 ms, against a bus that answers in under one. After the loop learned
# to wait on the connection's own descriptor the same three numbers were 1.0 ms
# and 13 to 20 ms.
#
#   ./gradlew :libsound-session:classes
#   tools/mpris-latency/run.sh [publishes] [gap-ms] [rounds]
#
# On a private bus, started here and killed on the way out. The machine's own
# session bus is never touched: a stand that published a player onto somebody's
# desktop would be a stand nobody runs.
#
# The gap between publishes is deliberately not a round number. A cadence that
# divides into the loop's own interval holds one phase for the whole run and
# reports a single value, which reads like a constant and is an artefact.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="${TMPDIR:-/tmp}/libsound-mpris-latency.$$"
M2="$HOME/.gradle/caches/modules-2/files-2.1"

# Read out of the version catalogue rather than written here, so the stand
# cannot drift from what the build actually compiles against.
version() { grep -E "^\s*$1\s*=" "$REPO/gradle/libs.versions.toml" | head -1 | cut -d'"' -f2; }
KOTLIN="$(version kotlin)"
SLF4J="$(version slf4j)"

jar() {
  find "$M2/$1/$2/$3" -name '*.jar' 2>/dev/null | grep -v sources | head -1
}

CP="$REPO/libsound-core/build/classes/kotlin/main"
CP="$CP:$REPO/libsound-dbus/build/classes/kotlin/main"
CP="$CP:$REPO/libsound-session/build/classes/kotlin/main"
CP="$CP:$(jar org.jetbrains.kotlin kotlin-stdlib "$KOTLIN")"
CP="$CP:$(jar org.slf4j slf4j-api "$SLF4J")"
CP="$CP:$(jar org.slf4j slf4j-simple "$SLF4J")"

for part in $(echo "$CP" | tr ':' ' '); do
  [ -e "$part" ] || { echo "missing on the classpath: $part" >&2; exit 1; }
done

mkdir -p "$OUT"
dbus-daemon --session --fork --print-address=1 --print-pid=3 \
  3>"$OUT/bus.pid" >"$OUT/bus.addr"
ADDR="$(cat "$OUT/bus.addr")"
BUSPID="$(cat "$OUT/bus.pid")"

cleanup() {
  [ -n "${MONPID:-}" ] && kill "$MONPID" 2>/dev/null || true
  kill "$BUSPID" 2>/dev/null || true
}
trap cleanup EXIT

# An outside observer, so the arrival time is stamped where a widget would see
# it rather than where we sent it.
DBUS_SESSION_BUS_ADDRESS="$ADDR" dbus-monitor --profile \
  "type='signal',interface='org.freedesktop.DBus.Properties',member='PropertiesChanged'" \
  >"$OUT/monitor.txt" 2>/dev/null &
MONPID=$!
sleep 0.4

probe() {
  DBUS_SESSION_BUS_ADDRESS="$ADDR" java --enable-native-access=ALL-UNNAMED \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=warn \
    -cp "$CP" "$HERE/Probe.java" "$@"
}

probe publish "${1:-20}" "${2:-137}" >"$OUT/publish.txt"

for players in 0 1 3; do
  echo "== players(), $players on the bus =="
  probe read "$players" "${3:-4}" | grep -E '^(PLAYERS|CONTROL)'
done

echo
echo "== publish() to the signal reaching an outside client =="
python3 - "$OUT" <<'REPORT'
import sys
out = sys.argv[1]
sent = [int(f[2]) for f in (l.split() for l in open(f"{out}/publish.txt")) if f and f[0] == "PUB"]
seen = [
    int(float(f[1]) * 1_000_000)
    for f in (l.split("\t") for l in open(f"{out}/monitor.txt"))
    if len(f) > 7 and f[0] == "sig" and f[7].strip() == "PropertiesChanged"
]
delays = sorted((next(s for s in seen if s >= t) - t) / 1000.0 for t in sent if any(s >= t for s in seen))
if not delays:
    print("no signals were seen, which is itself the answer")
else:
    n = len(delays)
    print(f"n={n}  min={delays[0]:.2f}  p50={delays[n // 2]:.2f}  "
          f"p90={delays[int(n * 0.9)]:.2f}  max={delays[-1]:.2f}  "
          f"mean={sum(delays) / n:.2f} ms")
REPORT

rm -rf "$OUT"
