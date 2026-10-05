# External consumer fixture

This is a separate Gradle Android application that consumes `com.qalens` coordinates through
`includeBuild("../..")`. It verifies a consumer build boundary rather than using the sample's
same-build `project(...)` dependencies. Its shared Application source compiles against the active
debug SDK and production no-op.

For a real host, follow [integration.md](../../integration.md) and the
[AI integration runbook](../../docs/AI_INTEGRATION.md). This fixture is a build/API example, not a
full QA app: it has no host Activity/UI, and its optional calls are partly compile-only or synthetic.
An unused example client and an API reference do not prove runtime capture in another app.
The focused instrumentation below opens the SDK Control Room; it is a runtime compatibility
check, separate from a complete host integration or capture acceptance test.

## Build from the SDK checkout

Use JDK 17 and an installed Android SDK with platform 35/build tools. Set `JAVA_HOME` and
`ANDROID_HOME` to the local installations; do not commit machine paths. The SDK root supplies the
Gradle wrapper. From that root:

```sh
./gradlew -p integration-tests/consumer assembleDebug assembleRelease verifyReleaseIsolation
./gradlew -p integration-tests/consumer dependencies --configuration debugRuntimeClasspath
./gradlew -p integration-tests/consumer dependencies --configuration releaseRuntimeClasspath
```

On Windows use `gradlew.bat`. Serialize Gradle processes sharing this checkout.
See [contributor setup](../../CONTRIBUTING.md) for portable prerequisites and SDK/device checks.
No Python bridge, backend, emulator, Maven publication or pairing token is needed for these builds.

## What is verified

### Newer Compose runtime smoke test

The reported Control Room `FlowRow` crash reproduced on API 36 with the old SDK compiled against
Foundation 1.7.6 and the host runtime using 1.8.2: `NoSuchMethodError` at SDK lines 330/304/564.
Current action groups use stable Compose UI `Layout`; all three experimental calls are removed.
The SDK's own compile versions stay on the repository baseline.

Build the consumer/test APKs with a newer debug runtime, then run on a disposable emulator:

```sh
./gradlew -p integration-tests/consumer -PqaComposeRuntime=1.8.2 assembleDebug assembleDebugAndroidTest
adb -s YOUR_DISPOSABLE_SERIAL install -r integration-tests/consumer/build/outputs/apk/debug/qalens-external-consumer-debug.apk
adb -s YOUR_DISPOSABLE_SERIAL install -r integration-tests/consumer/build/outputs/apk/androidTest/debug/qalens-external-consumer-debug-androidTest.apk
adb -s YOUR_DISPOSABLE_SERIAL shell am instrument -w \
  example.consumer.test/example.ControlRoomCompatibilityInstrumentation
```

Require `OK: External consumer Control Room renders` and no `FAIL:`/process crash. Repeat at
360×640 dp / 150% font / three-button navigation, then restore those emulator settings. The
test checks rescue, saved-recording and configuration action groups, visible nonoverlapping
recording buttons, and preserves settings while deleting its own synthetic layout file before
finishing. It does not play that placeholder file or send network data. The optional private cache
image is `qalens-consumer-control.png`; do not commit captures.

`qaComposeRuntime` changes only this fixture's debug/test runtime resolution for animation,
foundation, runtime and UI groups. The SDK compile classpath, material3, host-independent APIs and
production dependency mapping are unchanged. Confirm the evaluated versions with
`:qalens-compose:dependencyInsight` in the SDK build and `dependencyInsight` on this consumer's
`debugRuntimeClasspath`. Compilation alone is insufficient; CI builds the fixture, while the device
run proves its UI launches. Other runtime/OS/toolchain combinations need their own tests.

### Source and release checks

| File/check | Coverage |
|---|---|
| [settings.gradle.kts](settings.gradle.kts) | External settings/repositories and composite substitution of published coordinates |
| [build.gradle.kts](build.gradle.kts) | Debug active vs release no-op mapping; host-owned OkHttp/Timber; compile-only Room types |
| [ConsumerApplication.kt](src/main/java/example/ConsumerApplication.kt) | Same configuration, install, adapters, crash helpers, root/data hook and clip/bridge calls compile in both variants |
| `verifyReleaseIsolation` | Resolved release contains `qalens-noop` and excludes active Compose/Android/replay modules used by this fixture |
| [ControlRoomCompatibilityInstrumentation.kt](src/androidTest/java/example/ControlRoomCompatibilityInstrumentation.kt) | Actual SDK Control Room with host runtime packaging; synthetic layout fixtures, not host capture acceptance |

`qalens-core` in release is expected. The fixture does not add the optional active navigation or
replay dependency, nor test all possible flavors. The host integration guide provides a broader
gate including navigation and every actual shipping classpath. Apply that gate to the host itself.

Also review merged manifests: the production build should have no QaLens-owned capture services,
Control/pairing/projection activities/receiver, FileProvider or initializer metadata. Preserve the
host's other libraries and permissions. A successful release compile alone is not isolation proof.

## What remains a host test

- Existing Application/DI/startup ordering, Compose root/window discovery and navigation.
- Actual client/log writes, Room invalidations, DataStore changes and cached snapshot coverage.
- QA bubble, Control Room, recording/clip replay, consent/foreground errors and host responsiveness.
- Phone-approved desktop connection, two-way selection, actions and retained file transfers.
- Production flavor graphs, manifests and behavior on the company's real devices/toolchain.

The fixture proves source/build compatibility at the evaluated SDK revision. It does not establish
compatibility with every previously compiled client, all Compose versions or physical devices.
Never run the sample instrumentation against a customer package or reset host data as validation.
