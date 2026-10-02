# Contributing to QaLens

Start with [HANDOVER.md](HANDOVER.md), the [integration guide](integration.md), [current backlog](next.md) and
[OSS integration contract](docs/OSS_INTEGRATIONS.md). A small reproduction and regression test are
more useful than a broad rewrite. Use synthetic data in public issues and fixtures.

## Build locally

Use JDK 17, Android SDK 35 and Gradle 9.1.0. Set `JAVA_HOME` and `ANDROID_HOME` for your machine;
do not commit local SDK paths. The GitHub workflow records the complete verification matrix.

```sh
gradle :qalens-core:test :qalens-compose:testDebugUnitTest :qalens-replay:testDebugUnitTest :qalens-noop:testDebugUnitTest
gradle :qalens-compose:lintDebug :qalens-android:lintDebug :qalens-navigation-compose:lintDebug :qalens-replay:lintDebug
gradle :sample-app:assembleDebug :sample-app:assembleRelease :sample-app:verifyReleaseIsolation
gradle -p integration-tests/consumer assembleDebug assembleRelease verifyReleaseIsolation
node web/test/read.test.js
python3 backend/tests/test_backend.py
python3 tools/local-bridge/test_server.py
python3 tools/local-bridge/test_workbench.py
python3 tools/local-bridge/test_desktop.py
node --check tools/local-bridge/app.js
```

The consumer fixture resolves normal `com.qalens:*` coordinates through `includeBuild`; it catches
integration failures that same-repository `project()` dependencies miss. Device checks are described
in [recording retention](docs/RECORDING_RETENTION.md), [OSS integrations](docs/OSS_INTEGRATIONS.md)
and [client safety](docs/CLIENT_SAFETY_FIXES.md).

For the expanded Android regression runner, use a disposable emulator (replace its serial if needed):

```sh
gradle :sample-app:assembleDebug :sample-app:assembleDebugAndroidTest
adb -s emulator-5554 install -r sample-app/build/outputs/apk/debug/sample-app-debug.apk
adb -s emulator-5554 install -r sample-app/build/outputs/apk/androidTest/debug/sample-app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require the runner's `OK:` message. An adb process exit code alone does not prove assertions passed.
The runner changes sample activity contents/preferences and creates synthetic recordings; HTTP
fixtures bind loopback only.

For only the overlay/log-flood regression, use the same installed APKs on a disposable emulator:

```sh
adb -s emulator-5554 shell pm grant com.qalens.sample android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell input keyevent KEYCODE_WAKEUP
adb -s emulator-5554 shell wm dismiss-keyguard
adb -s emulator-5554 shell am instrument -w -e overlayLoadOnly true com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Overlay/Repro/Logs/Network remain responsive`. The case sends a 12,000-line burst,
then keeps background/main-thread logs and network observations flowing during tab switches.
`adb logcat -d -s QaLensLoadTest:I` shows its worst measured main-thread heartbeat delay; each
heartbeat must complete within 2.5 seconds. This is a synthetic regression, not a real-host ANR trace
or a frame-rate guarantee. The full runner includes the same case.

## Review expectations

- Keep pure policies and models in core; Android behavior belongs in the Android modules.
- Give every public SDK API a release no-op twin. Extend the consumer fixture and parity check.
- Observation must preserve host requests, response streams, cancellation and exceptions.
- Keep optional tools optional. Use their public APIs and record tested versions; do not inspect
  private databases or invent reflection hooks. Add a compileable example and relevant tests.
- Preserve both replay viewers and old archives. Add coverage metadata when capture is partial.
- Distinguish tested behavior from roadmap items. Never infer app health from missing evidence.

## Distribution

Local/composite integration and a generated Maven repository are supported. This repository does
not establish that `com.qalens` artifacts are available from Maven Central. Run
`scripts/release_internal.sh --verify` for a checked local zip, or add `--version 0.9.0-preview1`
for a preview with matching coordinates across modules. The script clears stale generated
publications before packaging. Publishing externally is a separate step.


For only the local bridge and inspector gesture cases, install the same debug/test APKs and run:

```sh
adb -s YOUR_DISPOSABLE_EMULATOR shell am instrument -w -e bridgeOnly true \
  com.qalens.sample.test/com.qalens.sample.RecordingRetentionInstrumentation
```

Require `OK: Local bridge semantics/actions/privacy/shutdown and LTR/RTL inspector gestures pass`.
The full runner includes this case. It injects real single/two-pointer gestures and validates filters
against current system/IME insets; run with gesture and three-button navigation. The
[PC bridge guide](tools/local-bridge/README.md) describes pairing and adb-forward ownership.
