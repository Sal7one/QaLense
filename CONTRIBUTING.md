# Contributing to QaLens

Start with the [integration guide](integration.md), [current backlog](next.md) and
[OSS integration contract](docs/OSS_INTEGRATIONS.md). A small reproduction and regression test are
more useful than a broad rewrite. Use synthetic data in public issues and fixtures.

## Build locally

Use JDK 17, Android SDK 35 and Gradle 9.1.0. Set `JAVA_HOME` and `ANDROID_HOME` for your machine;
do not commit local SDK paths. The GitHub workflow records the complete verification matrix.

```sh
gradle :qalens-core:test :qalens-compose:testDebugUnitTest :qalens-noop:testDebugUnitTest
gradle :qalens-compose:lintDebug :qalens-android:lintDebug :qalens-navigation-compose:lintDebug :qalens-replay:lintDebug
gradle :sample-app:assembleDebug :sample-app:assembleRelease :sample-app:verifyReleaseIsolation
gradle -p integration-tests/consumer assembleDebug assembleRelease verifyReleaseIsolation
node web/test/read.test.js
python3 backend/tests/test_backend.py
```

The consumer fixture resolves normal `com.qalens:*` coordinates through `includeBuild`; it catches
integration failures that same-repository `project()` dependencies miss. Device checks are described
in [recording retention](docs/RECORDING_RETENTION.md) and [OSS integrations](docs/OSS_INTEGRATIONS.md).

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
not establish that `com.qalens` artifacts are available from Maven Central. To produce a local
repository, run `gradle qalensDist`. A development version can be set with `-PqalensVersion=...`;
all module coordinates and publications use that version. Publishing externally is a separate step.
