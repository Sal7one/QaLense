# External consumer fixture

This is a separate Gradle Android application that consumes `com.qalens` coordinates through
`includeBuild("../..")`. It verifies a consumer build boundary rather than using the sample's
same-build `project(...)` dependencies. Its shared Application source compiles against the active
debug SDK and production no-op.

For a real host, follow [integration.md](../../integration.md) and the
[AI integration runbook](../../docs/AI_INTEGRATION.md). This fixture is a build/API example, not a
full QA app: it has no host Activity/UI, and its optional calls are partly compile-only or synthetic.
An unused example client and an API reference do not prove runtime capture in another app.

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

| File/check | Coverage |
|---|---|
| [settings.gradle.kts](settings.gradle.kts) | External settings/repositories and composite substitution of published coordinates |
| [build.gradle.kts](build.gradle.kts) | Debug active vs release no-op mapping; host-owned OkHttp/Timber; compile-only Room types |
| [ConsumerApplication.kt](src/main/java/example/ConsumerApplication.kt) | Same configuration, install, adapters, crash helpers, root/data hook and clip/bridge calls compile in both variants |
| `verifyReleaseIsolation` | Resolved release contains `qalens-noop` and excludes active Compose/Android/replay modules used by this fixture |

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
