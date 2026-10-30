#!/usr/bin/env bash
# Import a real Google Takeout archive into a throwaway Geobook and fail on any regression.
# Usage: scripts/takeout-smoke.sh [archive], default google-timeline.tar.gz at the repo root.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
archive="${1:-$root/google-timeline.tar.gz}"
jar="$root/target/tracker-0.0.1-SNAPSHOT.jar"

if [[ ! -f "$archive" ]]; then
  echo "Skipping Takeout smoke import: $archive not found."
  exit 0
fi

if [[ ! -f "$jar" ]]; then
  "$root/mvnw" -q -B -f "$root/pom.xml" package -DskipTests
fi

parent=/tmp
[[ -d /dev/shm && -w /dev/shm ]] && parent=/dev/shm
work="$(mktemp -d "$parent/geobook-takeout-smoke.XXXXXX")"
server=""
cleanup() {
  local status=$?
  if [[ -n "$server" ]]; then
    kill "$server" 2>/dev/null || true
    wait "$server" 2>/dev/null || true
  fi
  rm -rf "$work"
  exit "$status"
}
trap cleanup EXIT

mkdir "$work/data"
GEOBOOK_DATA_DIRECTORY="$work/data" GEOBOOK_AUTH_MODE=public SERVER_PORT=0 \
  java -jar "$jar" > "$work/server.log" 2>&1 &
server=$!

port=""
for _ in $(seq 120); do
  port="$(sed -n 's/.*Tomcat started on port \([0-9]*\).*/\1/p' "$work/server.log")"
  [[ -n "$port" ]] && break
  if ! kill -0 "$server" 2>/dev/null; then
    cat "$work/server.log" >&2
    echo "FAIL Geobook stopped before it started." >&2
    exit 1
  fi
  sleep 1
done
if [[ -z "$port" ]]; then
  echo "FAIL Geobook did not start within 120 s." >&2
  exit 1
fi

echo "Importing $archive on port $port"
start=$SECONDS
curl -sS --fail -N -F "file=@$archive" "http://127.0.0.1:$port/api/imports" > "$work/events.ndjson"
elapsed=$((SECONDS - start))

python3 "$root/scripts/takeout_report.py" "$work/events.ndjson" "$work/server.log" "$elapsed"
