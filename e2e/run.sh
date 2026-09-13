#!/usr/bin/env bash
# End-to-end: a real DeepSeek Harness with dsh-hive-vessel installed, the
# hive.deepseek addon (demo process) pushing to it, and headless Chromium
# checking the page.
#
#   e2e/run.sh WORK-DIR [CHROMIUM]
#
# WORK-DIR is scratch space: it receives an npm install of @deepseek-ai/dsh and
# playwright-core, an isolated DSH_HOME, a demo workspace and the report.
# Needs node >= 22, pnpm, clojure, and a Chromium (playwright's cache works).
#
# SPDX-License-Identifier: MIT
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mkdir -p "$1" && cd "$1" && pwd)"
CHROME="${2:-$(ls -d "$HOME"/.cache/ms-playwright/chromium-*/chrome-linux*/chrome 2>/dev/null | tail -1)}"
DSH_VERSION="${DSH_VERSION:-0.1.5-rc.1}"
BRIDGE_PORT="${BRIDGE_PORT:-7925}"
WEB_PORT="${WEB_PORT:-3182}"
OUT="$WORK/out"

cd "$WORK"
[ -f package.json ] || npm init -y >/dev/null
[ -x node_modules/.bin/dsh ] || npm i "@deepseek-ai/dsh@$DSH_VERSION" playwright-core >/dev/null
export DSH_HOME="$WORK/home"

if [ ! -d "$DSH_HOME/profiles/hivetest" ]; then
  npx dsh --profile hivetest --from-default-profile web --dump-config >/dev/null
  npx dsh plugin --profile hivetest add "$REPO/dsh" >/dev/null
fi

mkdir -p ws-demo "$OUT"
printf 'line one\nline two\nline three\n' > ws-demo/notes.txt
cp "$REPO/e2e/probe.mjs" "$WORK/probe.mjs"

listener_pid() { { ss -ltnp 2>/dev/null | grep ":$1 " | grep -o 'pid=[0-9]*' | head -1 | cut -d= -f2; } || true; }
free_port() {
  local pid; pid="$(listener_pid "$1")"
  [ -n "$pid" ] && kill "$pid" 2>/dev/null || true
  for _ in $(seq 1 30); do [ -z "$(listener_pid "$1")" ] && return 0; sleep 1; done
  echo "port $1 still busy" >&2; return 1
}

pids=()
# npx and clojure launch child processes that outlive their parents, so the
# listening ports are what gets cleaned up.
cleanup() {
  for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done
  free_port "$WEB_PORT" || true
  free_port "$BRIDGE_PORT" || true
}
trap cleanup EXIT
free_port "$WEB_PORT"
free_port "$BRIDGE_PORT"

# No display: dsh then mounts its in-browser directory picker, which a
# headless browser can drive.
env -u DISPLAY -u WAYLAND_DISPLAY npx dsh --profile hivetest --no-open --port "$WEB_PORT" > "$OUT/dsh.log" 2>&1 &
pids+=($!)

(cd "$REPO" && clojure -Sdeps "$(cat local.deps.edn)" -M:dev -m hive-deepseek.demo \
   "$BRIDGE_PORT" 20 "$WORK/ws-demo/notes.txt" e2e/ready) > "$OUT/demo.log" 2>&1 &
pids+=($!)

for _ in $(seq 1 90); do grep -q "dsh web:" "$OUT/dsh.log" && break; sleep 1; done
for _ in $(seq 1 120); do grep -q ":started" "$OUT/demo.log" && break; sleep 1; done
URL="$(grep -o "http://127.0.0.1:$WEB_PORT/?token=[^ ]*" "$OUT/dsh.log" | head -1)"

status=0
node probe.mjs "$URL" "$OUT" "$CHROME" "$WORK/ws-demo" "$WORK/ws-demo/notes.txt" || status=$?

for _ in $(seq 1 60); do grep -q ":health" "$OUT/demo.log" && break; sleep 1; done
echo "--- demo"
cat "$OUT/demo.log"
exit "$status"
