# AI agent runbook: integrate QaLens into an existing Android app

Updated 2026-10-05. This runbook is for an agent working in a **consumer's host repository**.
The goal is a useful QA integration that preserves the host's behavior and excludes active capture
from every production variant. SDK maintainers start with [HANDOVER.md](../HANDOVER.md) instead.
Read the host repository's own instructions before editing it.

## Reading order and source of truth

1. [Onboarding](../ONBOARDING.md): SDK, offline web replay, Python desktop and test backend roles.
2. [Integration recipes](../integration.md): dependencies, startup, Compose, variants and acceptance.
3. [OSS contracts](OSS_INTEGRATIONS.md): only the network/log/data/crash tools the host already uses.
4. [Desktop guide](../tools/local-bridge/README.md): phone approval, two-way selection, selectors and files.
5. [Capture policy](CLIENT_SAFETY_FIXES.md), [recording clips](RECORDING_CLIPS.md) and
   [recording coverage](RECORDING_RETENTION.md) before changing privacy or recording configuration.

The evaluated SDK Git revision and installed artifact/APK are the baseline. Docs describe that
source, not every older binary named `0.9.0`. Check signatures before adapting an example. Do not
infer current behavior from an old changelog entry, an old host AAR or sample-only UI.
Default `com.qalens` coordinates do not imply Maven Central publication.

## A task brief to give an integrating agent

> Integrate QaLens into this app using the SDK's AI integration runbook and the host's repository
> instructions. First inspect our build variants, Application, Compose roots, navigation and actual
> network/log/data owners. Preserve those owners and our security/privacy behavior. Use active
> artifacts only in approved QA variants and the no-op artifact in all production variants; add a
> resolved-dependency gate. Keep capture privacy defaults and connect optional sources only where
> useful. Make phone/desktop inspection work through SDK controls rather than sample code. Build
> the affected QA and production variants, verify manifests, and exercise the host on an available
> disposable test device with synthetic data. Finish with changed files, exact SDK revision/version,
> variant coverage, observed evidence, unverified checks and rollback instructions. Do not invent
> APIs, credentials, BuildConfig fields or compatibility claims.

## Discover before editing

Inspect these facts in the host and record them in the completion report. Most are discoverable;
ask the owner only for missing decisions that materially affect the integration.

| Fact | What to establish |
|---|---|
| Repository/build | App and feature module names, Gradle wrapper, JDK, AGP, Kotlin, Compose and navigation versions; existing conventions/version catalog |
| Variant policy | All real build types/flavors and shipping combinations; which QA builds may capture; whether QA is debuggable |
| Identity | Installed `applicationId` for each QA flavor, version/environment fields and existing revision metadata; namespace is not applicationId |
| Startup | Existing Application, DI initialization, AndroidX Startup customization, Activity lifecycle and multi-process usage |
| UI ownership | Activity `setContent`, embedded ComposeViews, themes/insets, controllers, dialog/popup windows and stable test tags |
| HTTP/log owners | The client(s) that actually execute traffic, interceptors, retries/streaming, Timber trees or another logging pipeline |
| State owners | Room instances and real table names; DataStore Flows and lifetime; small cached allowlisted values |
| Privacy | Password/customer/payment screens, secure windows, capture policy, backup rules, optional uploads/HD/body capture |
| SDK source | Composite checkout + revision, or an internally published immutable artifact version; desktop protocol revision |
| Verification access | Disposable device/serial and synthetic account; builds possible without secrets; checks that require the owner |

The tested SDK baseline is minSdk 23, SDK 35, JDK 17, AGP 8.7.3, Kotlin 2.0.21, Gradle 9.1.0,
Compose BOM 2024.12.01/runtime 1.7.8 and Navigation Compose 2.8.5. This is a tested snapshot, not
a demand to rewrite the host's toolchain or proof of compatibility with every Compose version.
Evaluate conflicts first. Keep host-compatible optional-library versions; do not suppress Kotlin
metadata checks to make an incompatible library compile.

For unsupported or untested UI/tooling, state the limit and use the public adapter seam if one
exists. This SDK inspects Compose semantics; it does not provide full native View/WebView/iOS
automation or unrestricted access to private Compose state.

## Work order

### 1. Choose an SDK source and map dependencies

Use a pinned complete composite checkout for evaluation, or the company's internal Maven
repository for repeatable team builds. Preserve the host's repository/plugin configuration.
For local publication use a unique preview version and the same version on active and no-op
artifacts. Follow [dependency recipes](../integration.md#step-1--dependencies); the independent
[consumer fixture](../integration-tests/consumer/README.md) demonstrates external coordinates.

Map every real variant and every module importing QaLens. Active `qalens-compose` supplies the
overlay/recorder/Control Room; navigation and Android replay are optional. Shipping variants use
`qalens-noop`. Do not add active SDK dependencies globally and rely on a runtime DEBUG check.
Do not put active and no-op on one classpath. An active module's published release AAR still
captures. Shared `qalens-core` models in production are expected.

Add the [production isolation gate](../integration.md#custom-variants-and-shared-modules) to the
host and list every shipping runtime configuration. A custom QA variant may need a defined
`QALENS_ENABLED` field; never reference a field you have not found or added explicitly.

### 2. Preserve startup and the existing UI

Configure identity/privacy in the existing Application. Keep normal AndroidX Startup installation
unless the host deliberately owns installation order. Startup runs before `Application.onCreate()`:
an option set there cannot undo the initializer having run. For manual installation remove only
QaLens initializer metadata and call `QaLens.install(this)` after configuration. See the
[lifecycle recipe](../integration.md#step-3--identify-the-build-l1-strongly-recommended).

Wrap the existing Activity content once with `QaLensRoot` inside its current theme. Keep DI,
navigation, saved state, insets, edge-to-edge, window flags and exception behavior. The root tracks
that Activity; it is not a substitute for application-wide installation when Startup is absent.
Use `QaLensNavigationObserver` beside the existing NavHost to preserve typed routes/transitions.
Do not observe again if using the `QaLensNavHost` wrapper.

Never copy SampleApp, its Settings screen or its own QA bubble into the host. SDK-owned overlay
and Control Room provide inspection, recording and PC pairing. Do not import internal SDK UI
classes. Runtime `enabled=false` stops capture and bridge access; a root's local `enabled=false`
does not disable hooks installed elsewhere.

### 3. Attach useful evidence to its real owner

Use the [OSS guide](OSS_INTEGRATIONS.md) and verify each selected path with a real host event:

| Path | Correct attachment and proof |
|---|---|
| OkHttp | Add `QaLensOkHttpInterceptor()` to the executing DI client builder; perform a synthetic request; verify one observation and unchanged response/error behavior |
| Chucker | Keep its existing interceptor/privacy and host-compatible active/no-op artifacts; attach QaLens alongside it; verify Chucker's launcher and QaLens metadata separately |
| Another transport | Keep one `QaLens.networkSink("Name")`; record once from the completed-request/error callback; no second adapter for the same instrumented OkHttp call |
| Timber | Plant `QaLensTimberTree()` once beside existing trees; emit a host log; do not replace or uproot the host's trees |
| Room | `observeRoom(db, "actual_table")` on the real database; write once; see table invalidation; stop observing before its owner closes |
| DataStore values | `observeDataStoreValues("Settings", dataStore.data) { prefs -> /* allowed key/value map */ }`; verify initial/foreground fields in Control Room, a real change and redacted recording state; stop with the owner |
| DataStore changes only | `observeDataStore("Prefs", dataStore.data) { "settings updated" }`; verify a later change label; stop with the owner |
| Current state | `registerDataSource` with a small cached allowlisted map; verify its redacted recording snapshot; no synchronous I/O in the provider |
| Crash vendor | Public `reportCrash`/bridge contract only if requested; preserve original crash/coroutine delivery; enrichment must not re-report the crash |

No hook means unknown coverage. Constructing a client/interceptor declares a source but does not
prove that app traffic uses it. `QaLens.integrationReport()` is useful for wiring, not a completeness
certificate. Default metadata omits bodies; unknown-length/SSE/large bodies stay metadata-only.
Never consume a one-shot body, swallow an exception or change cancellation to feed QA tooling.

Room observes invalidations, not SQL queries/rows. The event-only DataStore hook skips its initial
value; the value hook maps it on a worker into a bounded cache without a change event. Use the real
host-owned Flow and serializer, including app-owned decryption; never create a second DataStore
for inspection. Allowlist fields and inspect credential masking/current rules, disable/re-enable,
completion/error status and stop cleanup. [App data](APP_DATA.md) has the recipe and acceptance.
Reusing a DataStore name replaces its binding; choose one observation API per name. Pair each observer's
lifetime with its stop call. Snapshot registrations are process-lived: capture an app-owned cache,
not an Activity, screen or closed database. Do not invent `unregisterDataSource`; inspect the
current public API if a different lifetime is required.

Continuous logs need bounded messages and modest custom redaction patterns. Public `build*Report`
and `evidenceBundle()` are synchronous: invoke heavy reports on a worker. Snapshot providers still
run on main; cached lookups only. Do not add a blocking Room query, file read or network call to
Application/Compose/provider work to improve a report.

### 4. Make tags, windows and selectors useful to QA

Keep existing `Modifier.testTag` and accessibility semantics. Add stable, non-sensitive tags to
interactive buttons/fields and stable tagged ancestors where list items repeat. `qaName` is a
human label, `qaTag` adds a tag/layout hint, and neither manufactures a host action. Tap/type/scroll
require public Compose `OnClick`/`SetText`/`ScrollBy` semantics.

Register dialog/popup content with `Modifier.qaInspectionRoot()`. Pair imperative
`registerComposeRoot(view)`/`unregisterComposeRoot(view)` with attached window ownership. The
context must resolve to the active Activity. See [window and tag recipes](../integration.md#compose-inspection-across-host-windows).

Android inspect/tag mode and Review evidence → Elements offer search and action/tag filters. QA can inspect
supported actions and copy tags/selectors. Node IDs are root-scoped and live; save stable tag
selectors for later work. XPath addresses QaLens's exported XML, not Appium XML. Prefer a unique
tag or tagged-ancestor selector over visible position/text. Check match counts before acting;
duplicate, truncated or changed targets are rejected. Offscreen uncomposed lazy items, hidden
subtrees, password values and private Canvas state cannot be queried around.

### 5. Connect the phone and desktop through SDK controls

Use Python 3.9+, adb and a browser. Start `python3 tools/local-bridge/server.py --gui` in the SDK
checkout, open the shown local URL and select the intended device/applicationId on Landing.
**Connect** offers a request to SDK Control Room; approve on the phone and return to the host.
No sample Settings, backend, pip/npm, Appium or Robot integration is needed. Manual token pairing
is an advanced compatibility path, not onboarding.

Verify phone selection → browser attributes and browser selection → SDK highlight with
**Link phone & web selection** enabled. Tree/Inspect selection is inspect-only; Control-mode mirror
taps/drags/wheel/long-press operate the whole phone through adb. Preview mode pauses input. Check
letterboxing, known rotation, stale-frame/device guards, Inspect outlines/selection and Control
return against the real host. Refresh updates same-element attributes. Search, supported actions, selector
builder/match checks and XML/JSON downloads are in Landing. **Send to PC**/Receive is a separate
captured export path; explicit Save JSON persists a hashed document. Merely selecting a live node
does not save a component file.

Auto connect remembers an app/profile and still requires fresh phone approval. Auto reconnect
repairs the owned forward in an approved session and never repeats a host action. Recording
collection is independently opt-in and copies newly completed masters/clips after stop. Screen
preview is separately opt-in, whole-phone, unmasked, memory-only and includes other apps.
Leaving/hiding Landing stops browser sampling; this does not sanitize a downloaded file.
See [desktop persistence and limits](../tools/local-bridge/README.md).

Verify Landing Start/Stop through the SDK recorder, Frames privacy masks, HD host opt-in and OS
consent/pending/cancel, clip marking without stopping, completed master/clip replay, and clean versus
Include overlay screenshots with visibility restored. Screenshot PNGs follow the masked app-window
policy; the sampled mirror is whole-phone/unmasked. Capability detection disables new capture
controls on an older SDK; an offline/reconnecting phone is a transport issue, not proof of SDK age.
Clip export still occurs after normal Stop. Replay after Stop is explicitly opted in and waits for
the exact master; disconnect/device change/navigation cancels it. Closing the desktop never silently
stops/discards an ongoing phone recording. Do not couple these paths to sample-app settings or code.

Keep the installed host and Python/browser protocol versions aligned. Rebuild/reinstall the host
QA APK when changing the SDK artifact; restart Python and refresh the browser when updating it.
Source edits alone do not replace an old Maven cache or installed APK.

### 6. Validate capture and optional team setup

Start with private, masked frame recording and synthetic data. Record, mark a short clip, keep
recording, stop and replay both finished archives. Retrospective marks do not stop the master;
separate clip files are finalized after normal stop. `.sal` is evidence, `.appsal` is app/team
configuration, component JSON is a selected public node snapshot and XML is the visible semantics
tree. They are not interchangeable imports or complete host state dumps.

Enable HD only with host authorization (`allowUnmaskedVideo=true`) and actual Android consent.
HD is full-display, unmasked media; verify start, deny, foreground-service errors, Stop and decoding
in the consuming app. Do not infer success from SDK sample tests. Current host/OEM validation
limits are in [the executed Android matrix](ANDROID_VERIFICATION.md).

Uploads are optional. The Python backend is an unauthenticated loopback test mock with deterministic
verdicts, not a hosted AI or production company service. Device upload uses `adb reverse` and an
endpoint ending `/webhook`; desktop inspection uses `adb forward` and a different protocol.
Follow [backend setup](../backend/README.md), preserve the host's cleartext policy and use only
synthetic evidence there. Do not globally relax TLS, pinning or cleartext to make a test pass.

Keep bearer tokens in local QA Profiles, not committed `.appsal` files. Review SQL/macro literals,
URLs, custom data and pixel evidence too. Apply host backup exclusions for private recordings or
QA-only backup policy; do not silently disable production backup. Disabling capture does not
erase exports/uploads or restore previously shared data.

### 7. Prove the integration and hand it over

Run the [host acceptance checklist](../integration.md#verification-checklist-run-these), host
tests appropriate to changed owners, production dependency gates and manifest review. Preserve
existing checks. Build evidence and runtime evidence are separate. With no device or missing
credentials, finish independent work and state exactly which runtime checks remain unverified.

Do not install SDK test fixtures over a customer app, reset its data or run the sample-specific
instrumentation against it. Report the actual device/OS/variant used. Leave one completion report
in the host's existing documentation location and link it from the host's integration instructions;
do not create a competing SDK backlog. [next.md](../next.md) remains the SDK's current backlog.

## Privacy review by surface

| Surface | Required integration decision |
|---|---|
| Text, URLs and semantics | Review host redaction and stable tags; query values/password/hidden protection do not make all ordinary text public |
| Cached app data | Allowlist small values and redact sensitive keys/patterns; do not stringify full preferences, entities or credentials |
| Network bodies and Chucker | Bodies stay off by default; review bounded previews and Chucker's independent storage separately |
| SDK frames/screenshots | Known regions are masked, secure windows refused; arbitrary pixels/separate windows require review; gallery/share are opt-in |
| HD and PC screen preview | Whole display is unmasked; review and enable explicitly; do not claim SDK frame masks apply |
| Files/config/uploads | Review literal macros/SQL and exported artifacts; backup, PC retention and server authentication/retention belong to their owners |

## Troubleshooting

The October Control Room `FlowRow` crash was reproduced as `NoSuchMethodError` at lines 330/304/564
when the compiled SDK used Foundation 1.7 and the host supplied 1.8.2. Current SDK action groups
use stable Compose UI layout instead. For that trace, verify the host resolves the updated SDK
and inspect its actual runtime dependencies; a same-BOM sample build cannot prove binary
compatibility. The [external consumer fixture](../integration-tests/consumer/README.md#newer-compose-runtime-smoke-test)
keeps SDK compilation on the repository baseline and exercises newer runtime packaging.

Start with the first failing boundary: dependency → lifecycle → host event/root → phone approval →
transport → desktop → saved output. Use synthetic examples and redacted traces. Do not fix a
failure by suppressing the host's errors or weakening its capture/security policy.

| Symptom | Check and supported correction |
|---|---|
| Unresolved QaLens coordinates | Complete composite path/substitution, repository folder/URL and exact published version; no assumed Maven Central package |
| Duplicate classes | Active and no-op on one variant, or transitive active `api` dependency; correct variant/module mapping |
| Unresolved calls in a feature module | Importing module lacks the compile dependency; app dependencies do not supply it backward |
| Kotlin metadata/build mismatch | Host/library versions; keep compatible Chucker/other OSS versions; do not bypass metadata validation |
| Bubble absent or SDK appears inert | Installed QA variant, active graph, master enabled flag and Startup metadata/manual installation; non-debuggable QA may have DEBUG=false |
| Duplicate overlay or route events | Remove copied/internal overlay setup; wrap root once; use observer or wrapper per controller, not both |
| No foreground host / empty live tree | Return from Control Room to resumed host; root/Activity lifecycle context; install explicitly if Startup was removed; register separate roots |
| Tagged element lacks actions | Tag must reach the node with real Compose action semantics; a tag/Box/name does not create a callback |
| Duplicate/stale XPath or node ID | Refresh; narrow with stable ancestor/tag; node IDs/positions are live; check matches rather than bypassing ambiguity safeguards |
| Dialog/lazy item absent | Register the attached separate window; scroll so lazy content exists; hidden/private/non-Compose content remains outside coverage |
| Network/log track empty | Hook the actual executing client/logger; confirm capture switches; emit an event; declaration alone is insufficient |
| Data changes absent | Real Room table names, same db instance, Flow owner/subscription and a write after the initial emission; snapshots need a separate cached provider |
| Overlay/Repro stutters | Check message size, expensive caller-thread regex, main-thread providers and synchronous report calls; keep Chucker's own load in view |
| Record returns to app but never starts | Current SDK version, installation/resumed Activity, enabled flag and visible recording error; frame first; HD also needs opt-in and consent |
| Clip button produces no file yet | It marks while capture continues; stop normally and inspect the finished master/clip list and declared omissions |
| HD crash/foreground-service failure | Record exact OS/app/SDK version and redacted stack; verify consent/resume/start error; keep StrictMode and OS permission rules intact |
| PC cannot find/connect app | Authorized adb device/serial and installed applicationId; active SDK exposes Control launcher; phone approval before listener starts |
| Desktop 404/missing selectors | Installed host artifact, Python bridge and browser are different revisions; update/restart/refresh all three |
| Transfers stop or collection appears empty | Same approved connection/device, selected opt-in, a newly finished file and retained session; revoke on auth changes; do not persist/reuse tokens |
| Preview selection refused | Fresh screen/tree dimensions and window origin must agree; reconnect/refresh instead of applying mismatched coordinates |
| Upload fails/HTTP 413 | Correct `/webhook`, adb reverse, host's permitted debug URL, credentials and server size limit; backend default is 64 MiB, not PC's 400 MiB archive limit |
| Production contains SDK UI/services | Runtime DEBUG guard is insufficient; fix resolved active dependencies, rerun every shipping gate and inspect QaLens-owned manifest entries |

For an SDK defect, preserve a small synthetic reproduction and the failing check in the SDK's
current backlog. An observed data change before a failed request is a temporal lead, not causation;
an empty report is not proof that nothing happened.

## Handoff template

Copy this structure into the host's normal integration notes and fill it with facts. Keep tokens,
customer data, local paths and real recordings out of committed documentation.

```markdown
# QaLens host integration

Date / owner:
SDK Git revision:
Artifact source and exact version:
Desktop revision (if used):
Host app/module and tested applicationId:

## Changes
- Files changed and why; existing Application/root/navigation/client owners preserved.
- QA variant → active artifacts and enabled policy.
- Every production variant → no-op; gate/configuration names.
- Optional sources connected; owners and teardown; sources deliberately not integrated.
- Stable tags and separately registered windows.

## Capture decisions
- Bodies/gallery/HD/PC preview/upload settings and who approved opt-ins.
- Allowlisted snapshot keys and redaction; backup/retention policy.
- Team .appsal location; credentials remain local.

## Evidence
- Build/gate commands and actual outcomes; merged-manifest review.
- Actual device/OS/variant, synthetic runtime cases and observed output.
- Phone↔browser selection/actions, selector uniqueness and recording/clip replay if tested.
- Unverified or blocked cases, reason and next concrete check; no implied pass.

## Operate and roll back
- QA install/build and desktop startup/approval instructions for this host.
- Disable SDK / Stop PC inspector for immediate capture shutdown.
- Remove owner-bound observers/trees/interceptors and root/navigation hooks if reverting.
- Revert integration dependencies/config/manifest changes; rebuild all affected variants.
- Existing exports/uploads require separate retention/deletion decisions; no host data reset.
```

## Public source index

Consult these definitions instead of guessing APIs. Internal classes are not consumer extension points.

| Contract | Source |
|---|---|
| Facade, installation, reports, data hooks and bridge entry points | [QaLens.kt](../qalens-compose/src/main/java/com/qalens/QaLens.kt) |
| Configuration builder/defaults | [QaLensConfig.kt](../qalens-core/src/main/kotlin/com/qalens/QaLensConfig.kt) |
| Activity root/lifecycle | [QaLensRoot.kt](../qalens-compose/src/main/java/com/qalens/QaLensRoot.kt) |
| Tag/name/privacy/window modifiers | [QaLensModifiers.kt](../qalens-compose/src/main/java/com/qalens/QaLensModifiers.kt) |
| Startup behavior (read-only implementation reference) | [QaLensActivityInstaller.kt](../qalens-compose/src/main/java/com/qalens/QaLensActivityInstaller.kt) |
| Navigation observer/wrapper | [QaLensNavigationAdapter.kt](../qalens-navigation-compose/src/main/java/com/qalens/navigation/QaLensNavigationAdapter.kt) |
| OkHttp / Timber adapters | [QaLensOkHttpInterceptor.kt](../qalens-compose/src/main/java/com/qalens/QaLensOkHttpInterceptor.kt), [QaLensTimberTree.kt](../qalens-compose/src/main/java/com/qalens/QaLensTimberTree.kt) |
| Generic network sink and event model | [QaLensNetworkCapture.kt](../qalens-core/src/main/kotlin/com/qalens/QaLensNetworkCapture.kt), [QaLensModels.kt](../qalens-core/src/main/kotlin/com/qalens/QaLensModels.kt) |
| Release facade mirror | [NoopQaLens.kt](../qalens-noop/src/main/java/com/qalens/NoopQaLens.kt) |
| Compiled external-call examples | [ConsumerApplication.kt](../integration-tests/consumer/src/main/java/example/ConsumerApplication.kt) |
| Real sample OSS ownership | [SampleOssTools.kt](../sample-app/src/main/java/com/qalens/sample/SampleOssTools.kt) |

Do not add undocumented methods such as `QaLens.initialize`, automatic Chucker transaction
listeners or a generic preferences dump. Use the actual facade/adapter contracts and report missing
coverage honestly.
