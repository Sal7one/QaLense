# QaLens Integration Guide (for developers and their AI agents)

This file is written to be followed mechanically — every step is copy-pasteable and verifiable.
QaLens is a **debug-only** QA evidence SDK for Android Jetpack Compose apps: floating QA panel,
session recording to a portable `.sal` file, raw-SQL/data tooling, macros, webhook upload for AI
analysis, and a release build that is a guaranteed no-op.

Requirements: Android app with Jetpack Compose, `minSdk >= 23`, Kotlin, AGP 8+.

---

## Step 1 — Dependencies

Option A (this repo as included builds / module projects):

```kotlin
// settings.gradle.kts of your app — adjust the path
includeBuild("../qalens-compose-overlay")   // or copy the qalens-* modules in
```

Option B (mavenLocal — run once in this repo: `./gradlew publishToMavenLocal`):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }
```

Option C (company-internal distribution — recommended for teams): run
`scripts/release_internal.sh --verify` in this repo. It produces `dist/qalens-0.9.0-repo.zip`, a
self-contained Maven repository with a `SHA-256SUMS` manifest (verify after transfer with
`shasum -a 256 -c SHA-256SUMS`). Host the unzipped folder anywhere (artifact server, internal
static host, even a shared drive) and consume it:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://artifacts.yourcompany.com/qalens-repo") } // or a local path
        google(); mavenCentral()
    }
}
```

Then in your **app module** `build.gradle.kts` — the debug/release split is the whole safety model,
copy it exactly:

```kotlin
dependencies {
    debugImplementation("com.qalens:qalens-compose:0.9.0")             // overlay, recorder, Control Room
    debugImplementation("com.qalens:qalens-navigation-compose:0.9.0") // auto route tracking (optional)
    debugImplementation("com.qalens:qalens-replay:0.9.0")             // on-device .sal player (optional)
    releaseImplementation("com.qalens:qalens-noop:0.9.0")             // identical API, does NOTHING
}
```

> The no-op artifact mirrors every public symbol. You never wrap QaLens calls in `if (DEBUG)`.

## Step 2 — Zero-code baseline (L0)

Nothing. AndroidX Startup auto-installs the overlay in debug builds. Build, run the debug variant:
you get the floating QA bubble, shake-to-open, the persistent notification, and a second launcher
icon **"QaLens Control"** (the Control Room). Verify: `adb shell ams package …` not needed — just
look for the bubble.

Manifest merging adds (debug only): `POST_NOTIFICATIONS` (requested at runtime),
`SYSTEM_ALERT_WINDOW` (optional floating stop chip), `INTERNET` (webhook), a FileProvider under
`${applicationId}.qalens.fileprovider`, the Control Room + player activities (own task affinities)
and two services. No action needed unless you have manifest conflicts.

## Step 3 — Identify the build (L1, strongly recommended)

In your `Application.onCreate()`:

```kotlin
QaLens.configure {
    appName = "My App"
    appVersion = BuildConfig.VERSION_NAME
    buildVariant = BuildConfig.BUILD_TYPE
    environment = "staging"                 // what this build points at
    expectedEnvironment = "staging"         // flags wrong-build testing in the panel
    gitSha = BuildConfig.GIT_SHA            // if you inject it
    featureFlags = mapOf("checkout_v2" to true)
    slowNetworkThresholdMs = 1500L
    addRedaction("internal-token-[a-z0-9]+")  // extra masking on top of the defaults
}
```

Wrap your root composable (better semantics + `testTagsAsResourceId` for UI tests):

```kotlin
setContent { QaLensRoot { App() } }
```

## Step 4 — Screens & navigation (L2)

Navigation Compose: replace `NavHost` with `QaLensNavHost` (same parameters, plus an optional
`routeNameMapper: (String?) -> String`). Every route change lands in the timeline, screen-visit
map, and recordings. Not using Navigation Compose? Call `QaLens.setScreen("Checkout", route)`
yourself on screen changes.

## Step 5 — Network & logs (L3)

```kotlin
OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor()).build()  // network track
Timber.plant(QaLensTimberTree())                                          // log track
```

Both are `compileOnly` deps of QaLens — it never forces OkHttp/Timber on you. Without the
interceptor the network tab and `.sal` network track stay empty, and `analysis.json.coverage`
explicitly says so (so AI analysis won't infer "no traffic").

## Step 6 — Enrichment (L4, all optional)

```kotlin
// Test tags (also what macros' `tap`/`type` target — tag your login fields!)
Modifier.qaTag("login.email.field")
Modifier.qaName("Submit payment")             // human label for reports

// App-owned data → panel, reports, .sal
QaLens.registerDataSource("Prefs") { mapOf("theme" to prefs.theme) }
QaLens.observeDataStore("Prefs", dataStore.data) { it.toString() }
QaLens.observeRoom(db, "accounts", "orders")  // table-change timeline events

// Feature flags (live provider, evaluated safely)
QaLens.setFeatureFlagProvider { flags.snapshot() }

// Deep-link smoke scenarios + screen contracts
QaLens.registerDeepLinkScenario("Open cart", "myapp://cart", expectedRoute = "cart")
QaLens.contract("Checkout") { requiresTag("checkout.submit"); requiresNoFailedNetwork() }
```

## Step 7 — Team setup via `.appsal` (recommended)

One JSON config per app package: panel style (QA-minimal vs full), webhook endpoint, saved SQL
queries, macros, watched prefs files. Build it in the web editor (`web/index.html` → **⚙ .appsal
editor**), commit it next to your app, and every tester imports it on-device:
**Control Room → App Config → ⤓ Import .appsal**. Personal identities (per-tester webhook
bearer/Jira user) are **QA Profiles** on the device, deliberately not part of `.appsal`.

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
The minimal QA panel surfaces the **5 most recently used macros** at the top. Verbs:
`deeplink <uri>` · `wait <ms>` · `tap <tag|text>` · `type <tag> <text>` · `record [video]` ·
`stop` · `screenshot` · `mark <text>`.

## Step 8 — The output artifacts

| Artifact | What / where |
|---|---|
| `.sal` recording | ZIP of frames-or-video + synced timeline/network/logs/state + `analysis.json` (precomputed digest) + `for_ai.md` (self-describing for AI). Record from the panel/notification/Control Room. Replay on-device, in `web/index.html`, or `node web/tools/sal_report.js file.sal` (exit 1 on failures — CI gate). |
| Webhook upload | Control Room → per recording **⇪ Webhook**: multipart `file` + `X-QaLens-App/-Version/-Env/-Device/-Platform/-User/-Sal-Name/-Sal-Size/-Digest` headers + query params. Your backend's response body is shown to the tester. |
| Screenshots | Annotated, auto-saved to **Photos → Pictures/QaLens** (Android 10+), share optional. |
| Bug reports | Redacted Jira/Slack/repro text via one-tap copy (`QaLens.buildJiraReport()` etc.). |

## Verification checklist (run these)

1. `./gradlew :app:assembleDebug` → install → QA bubble visible, "QaLens Control" icon exists.
2. `./gradlew :app:compileReleaseKotlin` → compiles against the no-op (API parity proof).
3. Record 10s, stop via the REC chip → `.sal` appears in Control Room → ▶ Play works.
4. If you set a webhook: **Test endpoint** returns your backend's response in the card.

## Hard rules (do not violate)

- Never ship `qalens-compose` in a release build — the `releaseImplementation(qalens-noop)` line
  is mandatory, and release behavior must be verified with `compileReleaseKotlin`.
- Don't put real bearer tokens in `.appsal` files you commit — export with secrets masked
  (default) and let each tester store their token in their on-device QA Profile.
- All exports are redacted by QaLens defaults (JWTs, auth headers, cookies, emails, cards,
  phones, long IDs) — add `addRedaction(...)` rules for your domain-specific secrets.

## Known limits (set expectations)

- Compose-first: semantics inspection covers Compose UI; classic Views appear only as frames.
- The timeline never fabricates events: no interceptor → no network rows; no Timber → no logs.
- Frame recording is ~2fps (permission-free); choose HD video (MediaProjection consent) for
  animation-level detail. `FLAG_SECURE` windows black out captures.
- `tap`/`type` need semantics: tag your interactive elements (`Modifier.qaTag`) or they fall back
  to text matching.
