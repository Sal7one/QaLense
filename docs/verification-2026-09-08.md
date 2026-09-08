# First-session verification — 2026-09-08

Shipped the P2 percentile correctness item: one internal nearest-rank calculation shared by
network health, exported analysis, and frame timing. Seven new tests cover boundary ranks and
consumer behavior. No public API, sample inclusion policy, or archive schema change.
Updated next.md, CODE_REVIEW.md, and CHANGELOG.md.

## Commands and results

Run from the repository root, before edits and again after implementation:

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home /Users/salehalanazi/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle :qalens-core:test :qalens-noop:testDebugUnitTest :qalens-compose:compileDebugKotlin :qalens-replay:compileDebugKotlin :sample-app:compileDebugKotlin :sample-app:compileReleaseKotlin
node web/test/read.test.js
python3 backend/tests/test_backend.py
node web/tools/sal_report.js web/sample.sal
./demo.sh quick
curl -fsS -o /dev/null http://127.0.0.1:8100/web/index-v2.html
curl -fsS -o /dev/null http://127.0.0.1:8100/web/landing.html
./demo.sh kill
```

| Check | Baseline | After fix |
|---|---|---|
| Gradle matrix | BUILD SUCCESSFUL, 163 tasks | BUILD SUCCESSFUL, 163 tasks |
| Core XML test results | 115 tests, 0 failures/errors | 122 tests, 0 failures/errors |
| No-op XML test results | 1 test, 0 failures/errors | 1 test, 0 failures/errors |
| Web reader | 32 assertions, ALL PASS | 32 assertions, ALL PASS |
| Backend | 15 tests, OK | 15 tests, OK |
| CLI | Exit 1, expected demo failures | Exit 1, expected demo failures |
| Demo | Backend ready, both pages served | Backend ready, both pages served |

Gradle reused up-to-date tasks; the changed core suite executed after the fix. Existing Gradle
and Android deprecation warnings remain. Demo servers were stopped after each smoke check.
The demo check confirms startup and HTTP availability, not visual UI or real-device behavior.

## Next work

A3 full facade decomposition remains the highest-priority architectural item. Other P2 review
findings, WebP frames, and real-device watchdog/crash-finalization validation remain open.
The backlog contains older duplicated historical sections; use HANDOVER.md and current code
when resolving contradictory status claims.
