# QaLens

QaLens is an MIT-licensed Android Jetpack Compose QA evidence SDK. Testers reproduce a bug in a
QA/debug build, inspect screen/network/log context, then export a bug report or a portable `.sal`
recording. Engineers can replay it on Android, in the browser, or inspect it with a Node CLI.
Release builds use a separate no-op artifact.

**New to QaLens? Start with [ONBOARDING.md](ONBOARDING.md).** It covers the Android SDK, web app,
Python desktop, local backend, recording/inspection workflows and practical team advice.

For development, read [HANDOVER.md](HANDOVER.md) for the current verified baseline and engineering
contracts, then [next.md](next.md) for the backlog.

**Integrating with an AI agent? Use the [host integration runbook](docs/AI_INTEGRATION.md).** It
covers discovery, safe variant mapping, real source hooks, phone/PC setup, troubleshooting,
acceptance and a completion template.

## What it does

- Floating QA panel, separate Control Room, Compose inspect/tag modes and accessibility checks.
- Observed activity timelines, installed build/device facts, request/log tracks and concrete
  element findings. Optional score/classification report APIs remain heuristic; incomplete
  evidence does not establish a healthy app.
- Optional OkHttp metadata, Timber logs, Room/DataStore changes, feature flags, application data,
  crash reporter bridges and generic network sinks. Supported Chucker coexistence keeps both tools.
- Text bug reports, screenshots, frame/video session recording, macros and background SQL tools.
- Android replay, v2/classic web viewers, CLI comparisons and optional webhook upload. The included
  Python backend is a local deterministic mock, not a hosted AI service.

## Integrate

Inside this repository, the sample uses:

```kotlin
dependencies {
    debugImplementation(project(":qalens-compose"))
    debugImplementation(project(":qalens-navigation-compose")) // optional route wrappers
    debugImplementation(project(":qalens-replay"))             // optional Android player
    releaseImplementation(project(":qalens-noop"))
}
```

For an external app, follow [integration.md](integration.md). Composite builds and local Maven
repositories are supported. Default coordinates are `com.qalens:<module>:0.9.0`; availability on
Maven Central is not claimed. The separate [consumer fixture](integration-tests/consumer/README.md)
builds both variants. Pin a revision or an immutable internal artifact version; map custom QA and
production flavors deliberately and verify each production graph.

AndroidX Startup installs the active overlay. Add `QaLensRoot`, navigation observation, an interceptor
and enrichment only where useful. Open it through the bubble, shake, notification or **QaLens
Control** launcher. Test on a QA/debug build with synthetic data.

## Privacy and host behavior

Screenshots default to private app cache. Known sensitive Compose regions are masked; secure
windows are refused. Gallery copies and full-display video require separate host opt-ins. Video
has no per-node masks. Text rules do not guarantee privacy for arbitrary pixels or custom data.
Network bodies are off by default; opted-in response previews skip SSE and unknown/large bodies.
QaLens does not control Chucker's independent storage. Coroutine helpers preserve the host's
uncaught-exception behavior in both active and no-op artifacts.

Read [client migration and limits](docs/CLIENT_SAFETY_FIXES.md) before enabling media or changing
capture policy. Runtime disable stops collection; it does not delete previously shared artifacts.
Configured uploads can retry later. Release isolation is checked by dependency gates as well as builds.

## Try the demo or build

```sh
./demo.sh quick                       # local backend + v2 web viewer
node web/test/read.test.js
python3 backend/tests/test_backend.py
```

QaLens is free and self-hostable under the MIT license; the included backend is a local test mock,
not a hosted multi-company service. See [DEMO.md](DEMO.md) for a short upload walkthrough and
[backend setup](backend/README.md) for the mobile and web send paths. The backend binds to loopback
by default and has no authentication or production storage guarantees. See
[CONTRIBUTING.md](CONTRIBUTING.md) for the complete JDK 17 / Gradle 9.1.0 Android build, lint,
consumer and device matrix. `./demo.sh test` is a convenience subset, not every CI/device check.

To replay offline, open `web/index-v2.html` and drop a `.sal`. Loading the bundled sample through a
button requires HTTP serving. `web/index.html` remains the classic fallback and `.appsal` editor.

## Documentation map

| Need | Document |
|---|---|
| Get started across SDK, web and Python tools | [Onboarding](ONBOARDING.md) |
| Take over development | [Handover](HANDOVER.md), [current backlog](next.md) |
| Understand modules and data flow | [Architecture](docs/ARCHITECTURE.md) |
| Understand overlay behavior and token coverage | [Overlay design](docs/OVERLAY_DESIGN.md) |
| Integrate into an app | [Integration](integration.md), [OSS contracts](docs/OSS_INTEGRATIONS.md) |
| Give an AI agent an integration work order and acceptance criteria | [AI integration runbook](docs/AI_INTEGRATION.md), [consumer example](integration-tests/consumer/README.md) |
| Understand client fixes and compatibility changes | [Client fixes](docs/CLIENT_SAFETY_FIXES.md) |
| Implement or inspect recordings | [SAL format](docs/SAL_FORMAT.md), [retention](docs/RECORDING_RETENTION.md), [clips](docs/RECORDING_CLIPS.md) |
| Pair phones, inspect components and process saved files | [Desktop guide](tools/local-bridge/README.md) |
| Run replay/upload tools | [Web](web/README.md), [backend](backend/README.md), [demo](DEMO.md) |
| Use capture actions, evidence views and PC connection | [Mobile overlay](docs/MOBILE_OVERLAY.md) |
| Use synchronized Android playback and event following | [Mobile replay](docs/MOBILE_REPLAY.md) |
| Query SQLite and connect searchable decoded DataStore values | [App data](docs/APP_DATA.md) |
| Build, verify or distribute locally | [Contributing](CONTRIBUTING.md) |
| Review history | [Changelog](CHANGELOG.md), Git history |

## Desktop and phone inspection

The [local PC tool](tools/local-bridge/README.md) links phone/browser selections, reads a live Compose
tree, searches tags/roles/actions and shows attributes, bounds and checked QaLens XPath selectors.
Tap, type and scroll are explicit host actions. It also reads recent observed logs/network metadata
and cached host data snapshots. Run `python3 tools/local-bridge/server.py --gui`, choose the host
QA package on **Landing → Connect** and approve on the phone through SDK Control Room. This works
in any active SDK integration; no sample Settings or backend is required. Return to the host for
live inspection. Save hashed component JSON explicitly, remember device/app profiles and run
locally configured processors. Release/no-op builds provide no listener or pairing UI.

On the phone, use two fingers to scroll in inspect/tag mode, and drag **Move inspector** to reposition
the filters and selected-node card. Bubble/dock movement follows physical screen directions in RTL.

The [local desktop launcher](tools/local-bridge/README.md) embeds the existing replay viewers alongside
phone pairing, Compose inspection, saved components, processing pipelines and adb/file tools.
[Long sessions and bug clips](docs/RECORDING_CLIPS.md) describes retrospective 10/20/60-second/custom marks
without stopping capture, plus opt-in finished-recording collection on your PC.

## License

[MIT](LICENSE). Copyright © 2026 Saleh Alanazi.
