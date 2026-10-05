# QaLens Integration Guide (for developers and their AI agents)

Updated 2026-10-05. Read [ONBOARDING.md](ONBOARDING.md) first for the product/tools overview.
AI agents integrating into an existing app should also follow the
[integration runbook](docs/AI_INTEGRATION.md): discovery, implementation, acceptance and handoff.
The recipes below use example module/variant names; adapt them to the host before running them.
QaLens is a **QA/debug** evidence SDK for Android Jetpack Compose apps: floating QA panel,
session recording to a portable `.sal` file, raw-SQL/data tooling, macros, webhook upload for AI
analysis, and a separate no-op artifact for release. Verify the release dependency graph as well
as compilation; merely compiling release does not prove active capture was excluded.

Requirements: Android app with Jetpack Compose and `minSdk >= 23`. The tested build baseline is
Kotlin 2.0.21, AGP 8.7.3, JDK 17, SDK 35 and Gradle 9.1.0. Check host toolchain compatibility
before choosing newer optional-library versions. Composite consumers also need
`android.useAndroidX=true` in their own `gradle.properties`.

| Host situation | Start here |
|---|---|
| Evaluate the SDK without changing app architecture | Dependencies → Startup baseline → privacy/build identity |
| Existing Compose and Navigation Compose app | One root wrapper; observe the existing controller in Step 4 |
| Custom QA variants or shared feature modules | Custom variants and shared modules below; verify every production graph |
| Network-heavy app, Chucker, Room or DataStore | Steps 5–6 and [OSS contracts](docs/OSS_INTEGRATIONS.md) |
| QA needs phone/PC selection, tags and selectors | [Local PC inspection](#local-pc-inspection-and-compose-control) |
| Agent needs a reviewable completion report | [Acceptance checklist](#verification-checklist-run-these) and [handoff template](docs/AI_INTEGRATION.md#handoff-template) |

---

## Step 1 — Dependencies

Option A (the complete SDK checkout as an included build):

```kotlin
// settings.gradle.kts of your app — adjust the path
includeBuild("../QaLense")   // path to the complete SDK checkout
```

The repository sets `com.qalens` coordinates on its projects so composite substitution works.
The separate [consumer fixture](integration-tests/consumer/README.md) verifies this path in debug
and release. Pin the SDK checkout to the evaluated revision. Copying module folders alone does not
copy the root build configuration or their transitive module dependencies.

Option B (mavenLocal): in the SDK checkout run
`./gradlew -PqalensVersion=0.9.0-integration.1 publishToMavenLocal`, then add `mavenLocal()` to the
host's existing repository list. Use that exact version for every QaLens artifact. Prefer a unique
version per evaluated revision: replacing an AAR under the same version can leave a consumer on a
cached older SDK. This is local publication, not availability on Maven Central.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }
```

Option C (company-internal distribution — recommended for teams): run
`scripts/release_internal.sh --verify` in this repo. It produces `dist/qalens-0.9.0-repo.zip`, a
Maven repository with a `SHA-256SUMS` manifest (verify from the extracted `qalens-repo/` folder with
`shasum -a 256 -c SHA-256SUMS`). Host the unzipped folder anywhere (artifact server, internal
static host, even a shared drive) and consume it:

The repository URL must address the extracted `qalens-repo/` directory, not the zip or its parent.
Google/Maven Central access is still needed for host and third-party dependencies.

For an internal preview version, run `scripts/release_internal.sh --version 0.9.0-preview1 --verify`
and use that exact version in every debug and release dependency. The script clears its generated
repository before publishing so a zip never mixes versions. The wrapper and CI use Gradle 9.1.0;
set `QALENS_GRADLE` to another executable only when testing a deliberate toolchain change.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://artifacts.yourcompany.com/qalens-repo") } // or a local path
        google(); mavenCentral()
    }
}
```

Then in your **app module** `build.gradle.kts`, select active artifacts only for approved QA variants.
The default source version is `0.9.0`; substitute your published version consistently:

```kotlin
dependencies {
    debugImplementation("com.qalens:qalens-compose:0.9.0")             // overlay, recorder, Control Room
    debugImplementation("com.qalens:qalens-navigation-compose:0.9.0") // auto route tracking (optional)
    debugImplementation("com.qalens:qalens-replay:0.9.0")             // on-device .sal player (optional)
    releaseImplementation("com.qalens:qalens-noop:0.9.0")             // compatible calls, capture/UI disabled
}
```

The no-op facade lets shared integration calls compile in production. Navigation still renders
the host's NavHost, and coroutine helpers preserve host exception delivery. Never put active and
no-op artifacts on the same variant: they contain overlapping classes. A published **release AAR
of an active module remains active**; production safety comes from selecting `qalens-noop`.

### Custom variants and shared modules

Inventory the host's actual build types/flavors before adding dependencies. `debug`/`release` are
examples, not a complete variant policy. Explicitly wire an existing QA build type with
`add("qaImplementation", "com.qalens:qalens-compose:VERSION")` and the optional active artifacts
it needs. `initWith(debug)` or `matchingFallbacks` alone is not evidence that the resulting runtime
graph has the intended dependencies. Every shipping variant must resolve no-op instead.

If a feature/library module imports `com.qalens`, that module needs a compatible compile dependency;
an app dependency does not flow backward into its libraries. Use variant-specific active/no-op
dependencies there too, or keep an app-owned adapter around the SDK. Avoid an unconditional
`implementation`/`api` dependency on an active module leaking into production transitively.

`BuildConfig.DEBUG` suits the simple debug/release example. A non-debuggable QA build may need an
explicit host flag. Define it before using it, and set it for every real build type:

```kotlin
android {
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") { buildConfigField("boolean", "QALENS_ENABLED", "true") }
        getByName("release") { buildConfigField("boolean", "QALENS_ENABLED", "false") }
        // In an existing qa build type, explicitly set QALENS_ENABLED=true too.
    }
}
```

Then configure `enabled = BuildConfig.QALENS_ENABLED`. A runtime flag is an additional switch;
it does not remove capture classes, resources or manifest entries from a production APK.

Add a host build gate, adapting **all** production runtime classpath names. This example belongs
in the Android app module's `build.gradle.kts`:

```kotlin
tasks.register("verifyQaLensProductionIsolation") {
    doLast {
        val productionClasspaths = listOf("releaseRuntimeClasspath") // include every shipping flavor
        val forbidden = setOf(
            "com.qalens:qalens-compose", "com.qalens:qalens-android",
            "com.qalens:qalens-navigation-compose", "com.qalens:qalens-replay"
        )
        for (name in productionClasspaths) {
            val modules = configurations.getByName(name).incoming.resolutionResult.allComponents
                .mapNotNull { component ->
                    component.moduleVersion?.let { "${it.group}:${it.name}" }
                }.toSet()
            check("com.qalens:qalens-noop" in modules) { "$name is missing qalens-noop" }
            check(modules.intersect(forbidden).isEmpty()) {
                "$name contains active QaLens modules: ${modules.intersect(forbidden)}"
            }
        }
    }
}
```

`qalens-core` is expected in both graphs; it contains shared pure Kotlin models/policies. Also
inspect the production merged manifest for QaLens-owned activities, services, receiver, provider
and Startup metadata. Preserve unrelated host permissions and other libraries' Startup providers.

## Step 2 — Zero-code baseline (L0)

No installation code is needed with normal Startup. AndroidX Startup auto-installs the active
overlay. Build and run the approved QA/debug variant:
you get the floating QA bubble, shake-to-open, the persistent notification, and a second launcher
icon **"QaLens Control"** (the Control Room). Verify the bubble on a running debug build.

Manifest merging adds (active QA variants only): `POST_NOTIFICATIONS` (requested at runtime),
`SYSTEM_ALERT_WINDOW` (optional floating stop chip), `INTERNET` (webhook), a FileProvider under
`${applicationId}.qalens.fileprovider`, the Control Room + player activities (own task affinities)
and two services. Check the merged manifest for your variant; optional modules contribute only their own entries.
`ACCESS_NETWORK_STATE` supports connectivity observation.

## Step 3 — Identify the build (L1, strongly recommended)

Configure inside the **existing** `Application.onCreate()`, preserving `super.onCreate()` and
the host's initialization order. Do not replace its Application or DI setup with the sample's:

Use existing host metadata. The example assumes the app generates `BuildConfig`; if necessary,
enable `android.buildFeatures.buildConfig=true` in that module, as in the flag recipe above.
Do not assume these app fields exist in a library module or reference an undeclared `GIT_SHA`.

```kotlin
import com.qalens.QaLens

QaLens.configure {
    enabled = BuildConfig.DEBUG            // use the defined host flag for custom QA variants
    appName = "My App"
    appVersion = BuildConfig.VERSION_NAME
    buildVariant = BuildConfig.BUILD_TYPE
    environment = "staging"                 // what this build points at
    expectedEnvironment = "staging"         // flags wrong-build testing in the panel
    // gitSha = BuildConfig.GIT_SHA         // only if the host already defines this field
    featureFlags = mapOf("checkout_v2" to true)
    slowNetworkThresholdMs = 1500L
    captureNetworkBodies = false
    saveScreenshotsToGallery = false
    allowUnmaskedVideo = false
    addRedaction("internal-token-[a-z0-9]+")  // extra masking on top of the defaults
}
```

Wrap the Activity's existing Compose root once (better semantics + `testTagsAsResourceId` for UI
tests), inside its existing theme. Keep its navigation, insets and edge-to-edge setup:

```kotlin
import com.qalens.QaLensRoot

setContent {
    ExistingAppTheme {
        QaLensRoot { ExistingApp() }
    }
}
```

If you removed AndroidX Startup, `QaLensRoot` establishes the application context and tracks the
wrapped Activity's resume/pause lifecycle. `QaLens.configure` sets options; it does not install
application-wide hooks. For hooks and overlays across all host Activities, explicitly call
`QaLens.install(this)` in `Application.onCreate()`. The Control Room also installs those hooks
when you explicitly start recording from it. Keep the same no-op dependency split in production.

| Call/path | Responsibility |
|---|---|
| AndroidX Startup (normal integration) | Installs application-wide Activity hooks before `Application.onCreate()` |
| `QaLens.configure { ... }` | Sets identity, capture/privacy and behavior options; does not replace installation |
| `QaLens.install(application)` | Idempotent explicit installation when the host owns initialization |
| `QaLensRoot { ... }` | Registers the wrapped Activity/root and public semantics; tracks its lifecycle |

Do not render an internal `QaLensOverlay` or create another bubble. The SDK owns one overlay per
Activity window. `QaLensRoot(enabled = false)` skips that root's effects; use the master
configuration `enabled=false` to shut down process-wide capture and bridge access.

If the host intentionally disables the initializer, remove **only QaLens metadata** in the QA
variant manifest and explicitly install from the Application after configuration:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <application>
        <provider android:name="androidx.startup.InitializationProvider"
            android:authorities="${applicationId}.androidx-startup" tools:node="merge">
            <meta-data android:name="com.qalens.QaLensStartupInitializer" tools:node="remove" />
        </provider>
    </application>
</manifest>
```

```kotlin
override fun onCreate() {
    super.onCreate()
    QaLens.configure { /* host identity and privacy policy */ }
    QaLens.install(this)
}
```

Setting `enableAutoInstall=false` inside `Application.onCreate()` is too late to prevent Startup's
initial install. Do not remove the whole Startup provider; other libraries may use it. A root-only
integration without Startup handles that Activity, not every Activity in the process.

## Step 4 — Screens & navigation (L2)

For an existing Navigation Compose app, observe its current controller beside its existing
NavHost. This preserves typed routes, transitions and other host-specific overloads:

```kotlin
import com.qalens.navigation.QaLensNavigationObserver

// Inside the existing composition; existingNavController is the controller the app uses.
QaLensNavigationObserver(existingNavController)
// Keep the existing NavHost and graph unchanged.
```

Alternatively, `com.qalens.navigation.QaLensNavHost` supports a String `startDestination`,
`modifier`, optional `routeNameMapper: (String?) -> String?` and a graph builder. Compare the actual
signature before substituting it; it does not expose every typed-route/transition overload of
Navigation Compose. The wrapper already observes navigation: do not add a second observer to it.
Route changes join the timeline, screen-visit map and recordings. For another router, call
`QaLens.setScreen("Checkout", route)` from the existing route-change owner.

## Step 5 — Network & logs (L3)

```kotlin
import com.qalens.QaLensOkHttpInterceptor
import com.qalens.QaLensTimberTree
import okhttp3.OkHttpClient
import timber.log.Timber

OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor()).build()  // network track
Timber.plant(QaLensTimberTree())                                          // log track
```

These lines show attachment points, not a replacement networking stack. Modify the DI builder
that actually executes app requests; preserve authentication, TLS/pinning, cookies, cache, timeouts
and existing interceptors. Plant the Timber tree once in the logging owner, beside existing trees,
never from a composable. A newly constructed unused client cannot capture the host's traffic.

Both are `compileOnly` deps of QaLens — it never forces OkHttp/Timber on you. Without the
interceptor the network tab and `.sal` network track stay empty, and `analysis.json.coverage`
describes the declared sources. A generic sink can also supply observations; absence of events
does not establish absence of traffic.

### Capture feature flags (what feeds the tracks — and what doesn't)

Three `QaLensConfig` flags decide which automatic captures run (explicit
`QaLens.event()/log()/breadcrumb()` calls remain available while the master `enabled` flag is true):

```kotlin
QaLens.configure {
    captureNetwork = true        // default: QaLensOkHttpInterceptor logs metadata
    captureLogs = true           // default: QaLensTimberTree mirrors Timber lines
    networkFromChucker = false   // legacy flag; leave false (see Chucker below)
}
```

- **`captureNetwork = false`** — the interceptor becomes a pure pass-through (zero reads).
- **`captureLogs = false`** — the Timber tree drops every line.
- **Chucker coexistence:** attach both interceptors to the client that makes the request. Chucker
  owns its full-body inspector; QaLens captures its own metadata. Chucker has no public live
  transaction listener. The old `networkFromChucker` setting is retained for compatibility and
  no longer disables QaLens capture.

```kotlin
OkHttpClient.Builder()
    .addInterceptor(ChuckerInterceptor.Builder(context)
        .redactHeaders("Authorization", "Cookie", "Set-Cookie")
        .build())
    .addInterceptor(QaLensOkHttpInterceptor())
    .build()
```

Use matching Chucker debug/release artifacts. The sample pins **4.1.0** for this repository's
Kotlin 2.0.21 toolchain; 4.2.0 ships Kotlin 2.2 metadata and fails with this compiler. Existing host
apps should keep a Chucker version compatible with their own Kotlin/AGP toolchain. QaLens does not
add Chucker transitively. [Compatibility details and other OSS adapters](docs/OSS_INTEGRATIONS.md).

For another transport, create `val sink = QaLens.networkSink("Ktor")` once and call
`sink.record(NetworkEvent(...))` from the completed-request/error callback. No extra dependency is
needed. Capture switches, redaction and body opt-in apply to all adapter events. Do not mirror
traffic already observed by QaLensOkHttpInterceptor.

Open Overview → **Copy integration check**, or call `QaLens.integrationReport()`, to inspect declared
sources and settings without exposing request contents. `analysis.json.coverage.networkSources`
records declared adapter names; declaration alone does not prove complete capture.

### Continuous logs and network traffic

The live dashboard publishes pending logs and requests in batches about every 100 ms. It keeps the
newest events: `maxEventHistory` defaults to 600 and is bounded to 20–10,000 entries, with a shared
1,048,576-character message/tag budget. Individual dashboard fields have 16,384-character previews
plus a truncation notice; network history keeps 250 requests. These limits bound text and entries,
not total heap use. Repro/Logs/Network use lazy rows, and evidence/filter/search processing runs on
background workers that keep progressing during a continuous stream.

Recording journals have [separate retention budgets](docs/RECORDING_RETENTION.md). Dashboard
eviction and preview truncation do not edit that journal. Chucker's own capture, storage and UI
remain governed by Chucker's settings. Keep network bodies off unless a bounded preview is needed.

Overlay report copies are prepared in the background. Clipboard output above 200,000 characters
is explicitly truncated; use a recording for full retained evidence. Public `build*Report()` and
`evidenceBundle()` calls remain synchronous for compatibility: call them from a worker, for example
`withContext(Dispatchers.Default) { QaLens.buildFullReport() }`. Initial input redaction still runs
on the logging caller's thread, and host snapshot providers still run on main; keep messages and
providers small and avoid expensive custom regular expressions.

## Step 6 — Enrichment (L4, all optional)

```kotlin
// Test tags (also what macros' `tap`/`type` target — tag your login fields!)
Modifier.qaTag("login.email.field")
Modifier.qaName("Submit payment")             // human label for reports

// App-owned, allowlisted snapshots → panel, reports, .sal
QaLens.registerDataSource("Prefs") { mapOf("theme" to cachedTheme.value) }
QaLens.observeDataStore("Prefs", dataStore.data) { "settings updated" }
QaLens.observeRoom(db, "accounts", "orders")  // table names only; no row contents

// Feature flags (live provider, evaluated safely)
QaLens.setFeatureFlagProvider { flags.snapshot() }

// Deep-link smoke scenarios + screen contracts
QaLens.registerDeepLinkScenario("Open cart", "myapp://cart", expectedRoute = "cart")
QaLens.contract("Checkout") { requiresTag("checkout.submit"); requiresNoFailedNetwork() }
```

`observeRoom` uses Room's invalidation tracker; it records changed table names, not queries or
rows. `observeDataStore` accepts the real DataStore `data` Flow or any `Flow<T>`, skips the initial
value, and records the host's short `describe` label for later changes. Do not stringify the whole
preferences object or include secrets in that label. Snapshot providers run during analysis on the
main thread: return a cached, allowlisted map, and use `redactKeys`/`redactAll` for sensitive
values. When the host closes a database or disposes a Flow owner, call
`QaLens.stopObservingRoom(db)` or `QaLens.stopObservingDataStore("Prefs")`.

Room/DataStore changes now refresh those snapshots for recording state samples. A `.sal` archive's
`analysis.json` reports observed Room and preference change counts and flags a data change within
five seconds before a failed request as a temporal lead. The lead is a question to investigate,
not a claim that the data change caused the failure. Empty counts can mean unchanged data or
missing hooks; check `analysis.json.coverage` and exercise a real write during integration.

### Compose inspection across host windows

QaLens reads the public Compose semantics tree from the active Activity's attached Compose roots.
Ordinary `setContent` and embedded `ComposeView`s in that window need no extra hook. Keep normal
`Modifier.testTag` and accessibility semantics; use `qaTag` only when you also want its optional
layout hint. Repeated tags are matched to semantics by bounds, but unique test tags remain best for
reliable macros and reports.

A Compose `Dialog`, `Popup`, or other window has a separate root. Register it at the content root:

```kotlin
Dialog(onDismissRequest = onDismiss) {
    Box(Modifier.qaInspectionRoot()) {
        // dialog content
    }
}
```

The modifier unregisters the window when its composition ends and is inert in release builds. For
a host-managed window, pair `QaLens.registerComposeRoot(view)` after attaching its Compose view with
`QaLens.unregisterComposeRoot(view)` when removing it. The view's context must belong to the active
Activity. `QaLens.invalidateInspection()` requests a debounced scan after a semantics-only update;
visual Inspect/Tag modes also refresh every 500 ms while open. Outside those modes there is no
continuous polling. The no-op artifact exposes the same calls.

In Inspect mode, **Actions** shows interactive nodes by default. Switch to All, Tagged, or Issues
when investigating, then tap an outline to see its redacted label, tag, size, and warnings or copy
the test tag. Node IDs are scoped to their Compose root. Detached and fully off-viewport nodes are omitted;
`qaHiddenFromReports()` excludes its subtree from reports. Semantics do not reveal arbitrary private
Compose state or custom Canvas content. Activity-window screenshots do not establish capture of
separate dialog windows, so review sensitive windows and `FLAG_SECURE` separately.

### Tag and action design

Use stable, non-sensitive tags on the interactive node. Existing `Modifier.testTag` is supported;
you do not need to replace the host's testing or accessibility semantics:

```kotlin
import androidx.compose.ui.platform.testTag
import com.qalens.qaName

Button(
    onClick = onSubmit,
    modifier = Modifier.testTag("checkout.submit").qaName("Submit checkout")
) { Text("Pay") }
```

Prefer unique tags or a stable tagged ancestor for repeated list items. Avoid customer IDs, tokens,
email addresses, translated text and coordinates as durable identifiers. `qaTag` adds a tag and
layout hint; it does not create a click handler. `qaName` adds a report label. Supported Compose
`OnClick`, `SetText` and `ScrollBy` handlers become tap, type and scroll actions. Tagging a surrounding
Box cannot make private Canvas callbacks automatable. SDK-specific modifiers are inert in no-op
builds; preserve normal host semantics needed by production tests/accessibility.

Live node IDs and positional XPath are session-dependent. Lazy content that is not composed or
fully offscreen cannot be found by the current visible-tree search. Hidden/password values are
excluded; do not remove privacy protection to make an automation query succeed.

## Step 7 — Team setup via `.appsal` (recommended)

One JSON config per app package: panel style (tester quick actions vs full developer diagnostics),
webhook endpoint, saved SQL queries, macros and watched prefs files. Start from
`web/sample.appsal`, edit it in `web/index.html` → **⚙ .appsal editor**, set the app package and
team defaults, then export and review the JSON. Keep the webhook blank until the company owns an
authenticated service; the bundled Python mock is only for loopback testing. Commit a secret-free
config next to the host app, and have each tester import it on-device via **Control Room → App
Config → ⤓ Import .appsal**. Personal identities (per-tester webhook bearer/Jira user) are **QA
Profiles** on the device, deliberately not part of `.appsal`.

### Macros (inside `.appsal` or created on-device)

One step per line. `tap`/`type` drive real Compose semantics actions and **wait up to 5s** for the
target, so a macro can complete an entire login unattended:

```
deeplink myapp://login
type login.email.field qa+payments@example.com
type login.password.field Secret123!
tap login.submit
wait 1500
mark logged in by macro
```

Targets: exact test tag first, then visible text, then content description (smallest match wins).
The tester quick-actions sheet keeps macros under **More tools**; it shows the **5 most recently
used** first. Verbs:
`deeplink <uri>` · `wait <ms>` · `tap <tag|text>` · `type <tag> <text>` · `record [video]` ·
`stop` · `screenshot` · `mark <text>`.

## Step 8 — The output artifacts

| Artifact | What / where |
|---|---|
| `.sal` recording | ZIP of frames-or-video + synced timeline/network/logs/state + `analysis.json` (precomputed digest) + `for_ai.md` (self-describing for AI). Completed archives live in app-private `files/qalens/recordings/`. Record from the panel/notification/Control Room. Replay on-device, in the primary `web/index-v2.html` viewer (or classic `web/index.html`), or with `node web/tools/sal_report.js file.sal` (exit 1 for observed failures, 2 for invalid or disclosed partial evidence without a failure). |
| Webhook upload | Tester sheet → **Send latest session**, or Control Room → per recording **⇪ Webhook**: multipart `file` + `X-QaLens-App/-Version/-Env/-Device/-Platform/-User/-Sal-Name/-Sal-Size/-Digest` headers + query params. The tester sees a short upload verdict; inspect the backend dashboard for full details. |
| Screenshots | Annotated, saved to private app cache; sharing and Photos copies are opt-in. |
| Bug reports | Redacted Jira/Slack/repro text via one-tap copy (`QaLens.buildJiraReport()` etc.). |

> **Testing against the mock backend** — no real server needed. Run `python3 backend/server.py`,
> then `adb reverse tcp:8000 tcp:8000` (emulator), point the Control Room webhook at
> `http://127.0.0.1:8000/webhook`, and hit **Test endpoint**. Uploads land on the dashboard at
> http://127.0.0.1:8000/ with a deterministic mock AI verdict. See [`backend/README.md`](backend/README.md).

## Verification checklist (run these)

Run from the **host checkout**, adapting `:app` and variant names:

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:verifyQaLensProductionIsolation
./gradlew :app:dependencies --configuration debugRuntimeClasspath
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

The gate above must first be added to that host. On Windows use `gradlew.bat`. Host CI should run
its production gate for every shipping flavor. SDK/sample/consumer CI does not verify a different
app's dependency graph. Inspect merged manifests too; keep unrelated host entries intact.

On a disposable test install with synthetic data, verify the relevant rows below. Mark optional
paths as **not integrated** rather than silently passing them; without a device, report runtime
checks as **unverified**.

| Path | Acceptance evidence |
|---|---|
| QA entry/lifecycle | Bubble and Control launcher appear; open/close panel, background/resume and system Back preserve host navigation |
| Production isolation | No-op resolved; active modules absent from every production graph; no QaLens capture components in merged manifest |
| Navigation | Visit two host screens; route names match the real controller and are not doubled |
| Tags/actions/windows | Find a stable button/field; inspect tag and actions; exercise tap/type/scroll on synthetic data; verify registered dialogs |
| Heavy logs/network | Keep traffic flowing while opening Repro, Logs, Network and inspector; observe responsiveness in this host |
| HTTP/log hooks | Make one real request through the host client and one host log; check metadata/redaction and unchanged response/error behavior |
| Room/DataStore | Perform a real table/preferences write; see a change event; recording snapshot contains only cached allowlisted state |
| Frame recording | Record 10–20s, stop from REC; open the finished `.sal` in a player and verify retained frames/events and coverage |
| Clip | Mark the last 10s while recording; master continues; stop, then open both master and clip archives |
| HD (only if approved) | Actual Android consent, start, REC/notification Stop and playable video; denied consent preserves the host and reports no capture |
| Phone/PC | Connect with phone approval; select on phone then browser, select in browser then phone; highlight does not invoke a host click |
| Selectors | Search tags/actions; check a unique QaLens XPath against the live tree; reject duplicate or changed targets |
| Optional PC collection | Enable after pairing; finish a new recording; verify its private PC copy and replay; no credentials in saved profiles |
| Optional upload | Test the configured endpoint, send synthetic archive, verify server receipt; authentication and retention belong to the chosen server |
| Privacy/shutdown | Exercise password/hidden/secure regions; disable SDK and verify capture/bridge stop; inspect reports and artifacts before sharing |

Use [CONTRIBUTING.md](CONTRIBUTING.md) and the [consumer fixture](integration-tests/consumer/README.md)
for SDK-side checks. The SDK instrumentation deliberately resets fixture state; do not point its
runner at an installed customer app. Emulator success does not certify another host, OEM or OS.

## Hard rules (do not violate)

- Every shipping production variant must resolve `qalens-noop` and exclude active capture modules.
  The `releaseImplementation` recipe covers the simple case; include custom build types/flavors.
  Build QA and production variants and verify their resolved dependencies and merged manifests.
- Don't put real bearer tokens in `.appsal` files you commit — export with secrets masked
  (default) and let each tester store their token in their on-device QA Profile.
- Structured exports use configured redaction rules; custom data, macro/SQL literals and pixels
  need explicit privacy review. Add domain-specific rules and use secure windows/hidden regions.

## Known limits (set expectations)

- Compose-first: semantics inspection covers Compose UI; classic Views appear only as frames.
- The timeline never fabricates events: no interceptor → no network rows; no Timber → no logs.
- Frame recording is permission-free and samples according to configured duration (about once
  per second for the default hour, with a 500 ms minimum interval). [Clip/media limits](docs/RECORDING_CLIPS.md)
  describe retention and omissions. HD video requires `allowUnmaskedVideo=true` plus
  Android consent and has no per-node masks. Secure windows are refused by screenshot/frame capture.
- `tap`/`type` need semantics: tag interactive elements (`Modifier.testTag` or `qaTag`); macros can
  fall back to text matching. A tag does not create a host action.

## Privacy defaults and AI integration procedure

Read [Android client fixes](docs/CLIENT_SAFETY_FIXES.md) for migration details. Keep these defaults
unless the host explicitly chooses otherwise:

```kotlin
QaLens.configure {
    enabled = BuildConfig.DEBUG
    captureNetworkBodies = false
    saveScreenshotsToGallery = false
    allowUnmaskedVideo = false
}
```

`takeScreenshot(share=false)` uses private cache. Compose password/hidden/redaction-matched regions
are masked; arbitrary pixels are not. Coroutine helpers delegate uncaught failures in debug and
release; they do not suppress exceptions. Imported webhook origin changes clear the local credential.
A secret-free `.appsal` may still contain literal passwords in macro steps or SQL: review before sharing.

Finished `.sal` archives live in the host app's private `files/qalens/recordings/` directory so
cache eviction or an app update does not silently remove them. QaLens moves older archives from
`cache/qalens/` when it next starts and keeps a legacy archive visible if a move fails. Uninstalling
the host app still deletes them. Host app backup settings also apply to this directory: exclude
`qalens/recordings/` from cloud backup and device transfer, or disable backup for the QA variant,
if recordings must remain only on the test device. The sample app disables backup.

The [AI integration runbook](docs/AI_INTEGRATION.md) owns the discovery/work order, failure diagnosis
and completion template. Use real host identity and existing configuration, preserve its behavior,
and report the SDK revision plus actual tested variants/device. For SDK development, start at
[HANDOVER.md](HANDOVER.md) instead.


## Local PC inspection and Compose control

Every active SDK integration supports desktop **Landing → Connect**. It discovers the installed
QaLens app, offers credentials through an adb-only (`DUMP` sender permission) SDK receiver, and opens
**Control Room → Approve desktop**. Access starts only after explicit phone approval, returns to the
host app and expires if not approved within two minutes. No host Settings or sample dependency.
Credentials stay in process memory; profiles persist only device/app settings.

From the SDK checkout on the PC:

```sh
adb devices -l
python3 tools/local-bridge/server.py --gui
# If adb is outside PATH:
# python3 tools/local-bridge/server.py --gui --adb /absolute/path/to/adb
```

The defaults are PC browser port 8765 and device bridge port 8766. Select the intended serial and
the host's **installed applicationId** (including a QA suffix), not its namespace, a website URL
or the sample package. Python 3.9+, adb and a browser suffice; this workflow needs no backend,
Robot installation or custom host Settings. Return to the resumed host screen after approval for
live semantics/actions. Control Room can receive captured exports while the host is paused, but
it is not a foreground host tree.

Manual **PC inspector** controls remain in **Control Room → Desktop connection**, the tester
**More tools** and full **Tools** tab for older desktops/custom ports. Start/token copy/rotation/Stop
share the listener. Tokens are hidden until shown, excluded from reports and marked sensitive on
Android 13+ clipboard copies. **Stop PC inspector** or disable stops access; re-enable needs approval
or an explicit start. The desktop’s screen preview is a separate explicit, whole-phone adb capture,
not a masked SDK screenshot. It is memory-only and must not be treated as sanitized evidence.

For custom host controls, `QaLens.startLocalBridge(token, port = 8766)` still exposes redacted visible
semantics and tap/type/scroll handlers on device loopback. `QaLens.stopLocalBridge()` closes it;
`QaLens.localBridgeStatus` reports readiness/failure. Keep custom token UI out of reports with
`qaHiddenFromReports()` and never persist/log it. SDK and custom controls share the same listener;
starting either replaces the previous pairing. It is off by default, stops on disable and requires
an explicit start after re-enable. No-op builds provide no listener or pairing UI.

The [PC tool and complete protocol](tools/local-bridge/README.md) create an adb forward and provide a
local browser tree/bounds inspector plus observed logs/network metadata/cached app-data snapshots.
Exact tag queries reject duplicates; IDs are root-scoped and live-session only. Hidden subtrees and
password values are excluded. Host privacy/allowlisting still govern ordinary text and snapshots.
Actions report handler acceptance, not proof of a completed workflow. This is Compose automation
inside your QA build, with no automatic capture or production service.

**Link phone & web selection** is enabled on Landing by default. Selecting in either inspector
updates the other; a browser highlight is inspection only. No Send step is required for live
selection. Refresh reloads changed attributes for the same selected element. Search supports tags,
labels, text, descriptions, roles, state and actions. Android **Actions & XPath** and browser
**Selectors** expose supported actions, scoped tag/content/position suggestions and live match
checks. Exported XPath targets **QaLens XML**, not Appium/UIAutomator XML. The bounded evaluator
supports node child/descendant paths, positions and attribute equality/and; it is not a general
XPath runtime. See the [selector schema and limits](tools/local-bridge/README.md#search-and-selectors).

The workbench also supports **Send to PC** beside Copy test tag: preview public component attributes,
values and visible tree position, then explicitly save hashed JSON. Hidden/password/custom values
follow the bridge's coverage rules. Device/package/activity profiles persist on the PC; pairing
credentials stay in memory. Locally configured processors consume saved component documents from
the GUI or `tools/local-bridge/process.py`. These exports are separate from `.sal` recording archives;
see the PC guide for schema, privacy, queue budgets and processor contracts.

Connection choices have separate effects:

| Choice | Behavior |
|---|---|
| Auto connect | Remembers device/app profile and requests fresh phone approval on desktop start |
| Auto reconnect | Repairs the owned adb forward in the current approved session; never retries host actions |
| Receive phone elements | Polls explicit captured Send to PC exports; independent of live selection linking |
| Collect finished recordings | Off by default; after enabling, copies newly finished masters/clips after stop; does not start recording |
| Screen preview | Explicit unmasked, memory-only whole-phone sampling; independent of SDK frame masks |

Profiles, saved documents and files live under the PC's `~/.qalens/bridge/` by default, with no
persisted pairing token. Authentication/device changes end access; transient busy reads should
not turn opted-in transfer choices off. When updating the SDK, rebuild/reinstall the **host QA APK**,
restart the Python bridge and refresh the browser. Pulling source alone does not update an older
published artifact or installed host. Keep phone and desktop protocol revisions aligned.

In inspect/tag mode, use **two fingers to scroll the host** and one tap to select/copy. Drag the
**Move inspector** handle to move the filters/detail dock. Bubble and dock coordinates are physical
and clamped above system navigation/keyboard bounds in both LTR and RTL.

### Long sessions and bug clips

The default duration is now 60 minutes, configurable through `recordingMaxDurationMinutes` (1–180).
`saveRecentClip(seconds = 20, label = "Checkout failed")` marks a retrospective interval without
stopping; REC → ★ Clip also provides 10/20/60-second presets and a custom duration. Clips become
separate `.sal` files after normal stop. See [recording clips](docs/RECORDING_CLIPS.md) for budgets,
keyframe timing, opt-in video/privacy, evidence coverage and opt-in PC collection. Both active and
no-op facades expose the API; the no-op does nothing. Saved archives now retain at most 30 files /
1 GiB, and host backup exclusions still apply.
