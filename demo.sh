#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════════
# QaLens demo — one command to see and test EVERY feature, with or without Android.
#
#   ./demo.sh            quick: mock backend + web player (Mission Control) in 10s
#   ./demo.sh android    build + install the sample app and script the full QA flow
#   ./demo.sh curl       drive every hook (mobile webhook, chunks, frontend ingest)
#   ./demo.sh test       run every test suite (node, python, gradle) as CI would
#   ./demo.sh kill       stop the local servers
#
# Zero-dependency: python3 (stdlib only) + node for the web player/tests.
# ═══════════════════════════════════════════════════════════════════════════════
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKEND_PID_FILE="/tmp/qalens-demo-backend.pid"
WEB_PID_FILE="/tmp/qalens-demo-web.pid"
BACKEND_PORT="${QALENS_BACKEND_PORT:-8000}"
WEB_PORT="${QALENS_WEB_PORT:-8100}"
GREEN='\033[32m'; BLUE='\033[34m'; YELLOW='\033[33m'; BOLD='\033[1m'; RESET='\033[0m'

say()  { printf "${BOLD}%s${RESET}\n" "$*"; }
info() { printf "${BLUE}  → %s${RESET}\n" "$*"; }
ok()   { printf "${GREEN}  ✓ %s${RESET}\n" "$*"; }

open_url() {
  if command -v open >/dev/null 2>&1; then open "$1";
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$1";
  else info "open in your browser: $1"; fi
}

wait_for() { # url tries
  for _ in $(seq 1 "$2"); do
    if curl -fsS "$1" >/dev/null 2>&1; then return 0; fi; sleep 1;
  done
  return 1
}

backend_up() {
  if [ -f "$BACKEND_PID_FILE" ] && kill -0 "$(cat "$BACKEND_PID_FILE")" 2>/dev/null; then
    ok "mock backend already running (pid $(cat "$BACKEND_PID_FILE"))"
    return 0
  fi
  info "starting mock webhook backend on :$BACKEND_PORT (data → backend/data/)"
  (cd "$ROOT" && python3 backend/server.py --port "$BACKEND_PORT" --data-dir backend/data \
      > /tmp/qalens-demo-backend.log 2>&1 & echo $! > "$BACKEND_PID_FILE")
  wait_for "http://127.0.0.1:$BACKEND_PORT/ping" 15 || {
    echo "backend failed to start — see /tmp/qalens-demo-backend.log"; exit 1; }
  ok "backend up: http://127.0.0.1:$BACKEND_PORT/"
}

web_up() {
  if [ -f "$WEB_PID_FILE" ] && kill -0 "$(cat "$WEB_PID_FILE")" 2>/dev/null; then
    ok "web player already running (pid $(cat "$WEB_PID_FILE"))"
    return 0
  fi
  info "serving the web player (Mission Control) on :$WEB_PORT"
  (cd "$ROOT" && python3 -m http.server "$WEB_PORT" > /tmp/qalens-demo-web.log 2>&1 & echo $! > "$WEB_PID_FILE")
  wait_for "http://127.0.0.1:$WEB_PORT/web/index.html" 15 || {
    echo "web server failed — see /tmp/qalens-demo-web.log"; exit 1; }
  ok "web player up: http://127.0.0.1:$WEB_PORT/"
}

cheat_sheet() {
  say ""
  say "━━━ QaLens demo cheat sheet ━━━"
  echo "  Mock backend dashboard ....... http://127.0.0.1:$BACKEND_PORT/"
  echo "  Showcase landing page ........ http://127.0.0.1:$WEB_PORT/web/landing.html"
  echo "  Integration guide (Kotlin) ... http://127.0.0.1:$WEB_PORT/web/integration.html"
  echo "  Web player (Mission Control v2) http://127.0.0.1:$WEB_PORT/web/index-v2.html?sample&t=24.6   ← the demo .sal ON the failing transfer"
  echo "  Web player → ⚙ Settings → Backend URL = http://127.0.0.1:$BACKEND_PORT  → then ⇪ Send to backend"
  echo "  Android (emulator) .......... adb reverse tcp:8000 tcp:8000"
  echo "  Android webhook URL .......... http://127.0.0.1:$BACKEND_PORT/webhook"
  echo "  curl drive every hook ........ ./demo.sh curl"
  echo "  run every test suite ......... ./demo.sh test"
  echo "  stop local servers ........... ./demo.sh kill"
  say ""
}

cmd_quick() {
  say "QaLens — quick demo (no Android needed)"
  backend_up
  web_up
  cheat_sheet
  open_url "http://127.0.0.1:$BACKEND_PORT/"
  open_url "http://127.0.0.1:$WEB_PORT/web/landing.html"
  open_url "http://127.0.0.1:$WEB_PORT/web/index-v2.html?sample&t=24.6"
  ok "The web player opened the bundled demo session right at the failing transfer (score 58, Backend/API)."
  info "Next: click ⇪ Send to backend in the player → watch the upload appear live on the backend dashboard."
}

cmd_curl() {
  say "QaLens — driving every hook with curl"
  backend_up
  B="http://127.0.0.1:$BACKEND_PORT"
  echo
  say "1) Mobile Test-endpoint ping (what the Control Room button sends):"
  curl -fsS -X POST -H "Content-Type: application/json" -d "{\"qalens\":\"webhook-test\"}" "$B/webhook" | python3 -m json.tool
  echo
  say "2) Mobile webhook — multipart .sal upload with X-QaLens-* headers:"
  curl -fsS -F "file=@$ROOT/web/sample.sal" \
      -H "X-QaLens-App: QaLens Sample" -H "X-QaLens-Env: staging" -H "X-QaLens-User: qa+saleh@example.com" \
      "$B/webhook?team=payments&pipeline=nightly" | python3 -m json.tool
  echo
  say "3) Frontend hook — web player JSON summary ingest:"
  curl -fsS -X POST -H "Content-Type: application/json" \
      -d "{\"app\":\"QaLens Sample\",\"version\":\"1.4.2\",\"environment\":\"staging\",\"platform\":\"web\",\"score\":58,\"likelyOwner\":\"Backend/API\",\"failedRequests\":2,\"sessionId\":\"curl-demo\"}" \
      "$B/api/ingest" | python3 -m json.tool
  echo
  say "4) Chunked/resumable upload (R7) — split sample.sal into 2 chunks, resume-aware:"
  python3 "$ROOT/scripts/demo_chunked.py" "$ROOT/web/sample.sal" "$B" 100000
  echo
  say "5) What the backend stored:"
  curl -fsS "$B/api/uploads" | python3 -c "import json,sys; [print(' ', u['id'], '|', u['source'], '|', (u.get('app') or {}).get('name'), '| score', u.get('score'), '|', (u.get('verdict') or {}).get('severity')) for u in json.load(sys.stdin)['uploads']]"
  ok "Everything above landed on the dashboard: $B/"
}

cmd_test() {
  say "QaLens — running every test suite (CI parity)"
  echo
  say "1/4 web .sal reader regression (node):"
  node "$ROOT/web/test/read.test.js" | tail -2
  echo
  say "2/4 mock backend end-to-end (python):"
  python3 "$ROOT/backend/tests/test_backend.py" 2>&1 | grep -E "^(Ran|OK|FAILED)"
  echo
  say "3/4 kotlin unit tests + release parity (gradle):"
  GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle"
  if [ ! -x "$GRADLE_BIN" ]; then
    info "cached gradle not found — using the wrapper (first run downloads ~130MB)"
    GRADLE_BIN="$ROOT/gradlew"
  fi
  (cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}" "$GRADLE_BIN" :qalens-core:test :qalens-compose:testDebugUnitTest :qalens-replay:testDebugUnitTest :qalens-noop:testDebugUnitTest :qalens-replay:compileDebugKotlin :sample-app:compileDebugKotlin :sample-app:compileReleaseKotlin :sample-app:verifyReleaseIsolation --console=plain 2>&1 | tail -6)
  echo
  say "4/4 CLI smoke (sal_report as a CI gate):"
  rc=0
  node "$ROOT/web/tools/sal_report.js" "$ROOT/web/sample.sal" > /tmp/qalens-demo-report.md || rc=$?
  if [ "$rc" -ne 1 ]; then echo "Unexpected CLI exit: $rc (expected 1)"; exit 1; fi
  echo "  sal_report exit=$rc (1 is CORRECT — the demo session contains failures)"
  head -8 /tmp/qalens-demo-report.md | sed "s/^/  /"
  ok "All suites done."
}

cmd_android() {
  say "QaLens — full Android demo (sample app + Control Room + webhook)"
  command -v adb >/dev/null 2>&1 || { echo "adb not found — install Android platform-tools first"; exit 1; }
  GRADLE_BIN="$HOME/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle"
  if [ ! -x "$GRADLE_BIN" ]; then GRADLE_BIN="$ROOT/gradlew"; fi
  info "building the sample app (debug)"
  (cd "$ROOT" && JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home}" "$GRADLE_BIN" :sample-app:assembleDebug --console=plain 2>&1 | tail -4)
  APK=$(ls "$ROOT"/sample-app/build/outputs/apk/debug/*.apk | head -1)
  ok "APK: $APK"
  info "installing on the connected device/emulator"
  adb install -r "$APK"
  info "wiring the device to the local mock backend (port 8000)"
  backend_up
  adb reverse tcp:8000 tcp:8000 || info "adb reverse failed — use your LAN IP instead"
  info "launching the sample app"
  adb shell am start -n com.qalens.sample/com.qalens.sample.MainActivity
  sleep 3
  say ""
  say "━━━ Scripted wow flow — paste these one by one ━━━"
  echo "  adb shell am start -a android.intent.action.VIEW -d qalenssample://account/2 com.qalens.sample   # open Account #2"
  echo "  # in the app: Transfer → 1500 → Confirm Transfer → watch it fail (POST /transfer 500)"
  echo "  adb shell am start -n com.qalens.sample/com.qalens.QaLensControlActivity              # open the Control Room"
  echo "  # Control Room → Webhook → http://127.0.0.1:8000/webhook → Test endpoint → ✓"
  echo "  # tap the QA bubble (or shake) → Overview: score dropped, Likely Owner = Backend/API (HIGH)"
  echo "  # Bug Bundle → Copy Jira Bug → paste anywhere"
  echo "  # Record Session → reproduce the transfer → stop → ⇪ webhook → verdict on the dashboard"
  say ""
  info "Dashboard: http://127.0.0.1:$BACKEND_PORT/  (watch uploads arrive live)"
}

cmd_kill() {
  for f in "$BACKEND_PID_FILE" "$WEB_PID_FILE"; do
    if [ -f "$f" ]; then kill "$(cat "$f")" 2>/dev/null || true; rm -f "$f"; fi
  done
  ok "local servers stopped"
}

case "${1:-quick}" in
  quick)   cmd_quick ;;
  backend) backend_up; cheat_sheet ;;
  web)     web_up; cheat_sheet ;;
  curl)    cmd_curl ;;
  test)    cmd_test ;;
  android) cmd_android ;;
  kill)    cmd_kill ;;
  -h|--help|help)
    sed -n "2,11p" "$0" | sed "s/^# //"
    ;;
  *) echo "unknown command: $1 (try: quick, backend, web, curl, test, android, kill)"; exit 2 ;;
esac
