# QaLens Integration Skill — for AI coding agents

**Purpose:** a deterministic, follow-in-order procedure for any AI coding agent (Claude, Cursor,
Copilot, DeepSeek, ...) to integrate the QaLens mobile release-evidence SDK into an existing
**Android Jetpack Compose** app. The agent inspects the project first, asks the operator a fixed
set of questions, applies the integration level by level, then verifies and reports.

**Reference files in this repo (use them — do not paraphrase the snippets):**
- `integration.md` — the human copy-paste guide (same snippets, more prose)
- `web/integration.html` — the highlighted Kotlin version (for humans)
- `demo.sh` + `DEMO.md` — how to demo and test everything
- `docs/ONBOARDING.md` — mental model, glossary, gotchas

**Hard rules (never violate):**
1. Release builds MUST get `releaseImplementation(qalens-noop)` — never ship the debug SDK in
   release. The no-op mirrors the public API, so app code needs no `if (DEBUG)` guards.
2. Never invent APIs that do not exist in the version you are integrating. Read
   `qalens-compose/src/main/java/com/qalens/QaLens.kt` for the public surface.
3. Never commit secrets or real user data; all demo values use example.com placeholders.
4. The SDK observes only what the app opts into — do not claim coverage the app did not wire.
5. `minSdk >= 23`, Kotlin + AGP 8+, JDK 17 (Kotlin 2.0.21 cannot parse JDK 25).

---

## Phase 0 — Inspect the host project (read-only)

Run these checks and fill the Detection Report. Everything in later phases depends on it.

```bash
# 1. Build system + versions
ls settings.gradle.kts settings.gradle 2>/dev/null
grep -nE "agp|com.android.application|kotlin\\(|minSdk|compileSdk" build.gradle.kts app/build.gradle.kts 2>/dev/null

# 2. Jetpack Compose (required for the overlay — if absent, see Edge Cases)
grep -rn "androidx.compose" app/build.gradle.kts 2>/dev/null

# 3. Navigation Compose (L2 auto-tracking)
grep -rn "navigation-compose" app/build.gradle.kts 2>/dev/null

# 4. OkHttp (L3 network track) — note the version for the Chucker decision
grep -rn "okhttp" app/build.gradle.kts 2>/dev/null

# 5. Chucker already installed? (L3 — Chucker coexistence option)
grep -rni "chucker" app/build.gradle.kts app/src 2>/dev/null

# 6. Timber already planted? (L3 log track)
grep -rn "Timber.plant\|TimberTree" app/src 2>/dev/null

# 7. Room / DataStore (L4 change events)
grep -rn "androidx.room\|androidx.datastore" app/build.gradle.kts 2>/dev/null

# 8. Crash reporter (L4 crash bridge)
grep -rni "sentry\|bugsnag\|crashlytics" app/build.gradle.kts app/src 2>/dev/null

# 9. Existing Application class (where L1 config goes)
grep -rln "Application()" app/src/main 2>/dev/null
```

### Detection Report (fill in before Phase 1)

| Check | Result | Consequence |
|---|---|---|
| Build system | KTS / Groovy / other | snippet dialect |
| Compose | present / absent | absent → overlay SDK cannot attach (see Edge Cases) |
| Navigation Compose | present / absent | present → L2 wrapper is a drop-in |
| OkHttp version | e.g. 4.12.0 | L3 interceptor (or Chucker ordering) |
| Chucker | present / absent | present → retain Chucker and add QaLens metadata interceptor in Q3 |
| Timber | planted / absent | planted → add QaLensTimberTree as a second tree |
| Room / DataStore | present / absent | L4 observers available |
| Crash reporter | name / none | L4 crash bridge target |
| Application class | path / none | where QaLens.configure lives |
| minSdk | value | must be >= 23 |

---

## Phase 1 — Ask the operator (one message, all questions, wait for answers)

Do not start editing before the operator answers. Defaults are shown first (recommended).

**Q1 — App identity.** Values for the L1 configure block: app name, version (e.g.
`BuildConfig.VERSION_NAME`), build variant (`BuildConfig.BUILD_TYPE`), environment name
(e.g. staging), expected environment, git SHA if injected. If the operator has no preference,
use BuildConfig values and environment = null.

**Q2 — Integration level.** (a) L0 dependency only [default if the operator wants the minimum]
(b) through L2 (nav tracking) (c) through L3 (network + logs) (d) full L4 [recommended — this is
where the bug-report value is]. The skill always applies L1 identity if the operator allows it.

**Q3 — Network source.** (a) QaLens interceptor [default] (b) Chucker is already installed — use
both ChuckerInterceptor and QaLensOkHttpInterceptor on the same client (c) no network capture (`captureNetwork = false`).

**Q4 — Logs.** (a) Plant QaLensTimberTree [default] (b) no automatic log capture
(`captureLogs = false`).

**Q5 — Body capture.** (a) off [default — metadata only] (b) on (`captureNetworkBodies = true`;
text-ish types, 64 KB cap, redacted twice).

**Q6 — Webhook.** (a) configure now against the mock backend
[recommended for testing] — `http://127.0.0.1:8000/webhook` with
`adb reverse tcp:8000 tcp:8000` (b) real endpoint URL + auth header now (c) later / none.

**Q7 — Release safety (inform, do not ask permission to skip).** State that the skill will add
`releaseImplementation(qalens-noop)` — this is mandatory; explain that it makes release a no-op
with an identical API.

**Q8 — Extras.** Register deep-link scenarios or screen contracts for the operator's key flows?
Import the sample `.appsal`? (No is fine.)

---

## Phase 2 — Add the dependencies

Apply the debug/release split EXACTLY (module-path form for this monorepo, coordinates for
external apps).

```kotlin
// settings.gradle.kts — Option A: modules inside this repo (or includeBuild)
includeBuild("../qalens-compose-overlay")   // or copy the qalens-* modules in

// Option B: published coordinates (run ./gradlew publishToMavenLocal once in the QaLens repo)
// settings.gradle.kts
// dependencyResolutionManagement { repositories { mavenLocal(); google(); mavenCentral() } }

// app/build.gradle.kts — THE safety model, copy exactly
dependencies {
    debugImplementation(project(":qalens-compose"))           // overlay, panel, recorder, Control Room
    debugImplementation(project(":qalens-navigation-compose")) // optional — auto route tracking
    debugImplementation(project(":qalens-replay"))             // optional — the .sal player
    releaseImplementation(project(":qalens-noop"))             // MANDATORY — release does nothing
}
```

If Q2 >= (c) and the app does not yet depend on OkHttp/Timber, add them (QaLens treats both as
compileOnly, so this is the app's own choice).

---

## Phase 3 — Apply the integration levels

### L0 — zero code
Nothing to write. AndroidX Startup installs the overlay, shake listener, notification, and the
Control Room launcher icon automatically in debug builds. Skip to verification.

### L1 — identify the build (Application.onCreate) + root wrapper

```kotlin
// In the Application class (create one if none exists and register it in the manifest)
QaLens.configure {
    appName = "<Q1 name>"
    appVersion = BuildConfig.VERSION_NAME
    buildVariant = BuildConfig.BUILD_TYPE
    environment = "<Q1 env or null>"
    expectedEnvironment = "<Q1 expected env or null>"
    gitSha = BuildConfig.GIT_SHA                 // only if the app injects it
    featureFlags = mapOf("<flag>" to true)        // Q1/Q8
    slowNetworkThresholdMs = 1500L

    // Capture feature flags (B17) — decide what feeds the tracks:
    captureNetwork = true          // false = interceptor is a pure pass-through
    captureLogs = true             // false = Timber tree drops every line
    networkFromChucker = false     // legacy flag; do not enable
    captureNetworkBodies = false   // true  = opt-in 64KB redacted body previews
}
QaLens.install(this)   // safe even when Startup already installed
```

```kotlin
// Root composable — better semantics + testTagsAsResourceId for UI tests
setContent { QaLensRoot { App() } }
```

### L2 — screens and navigation

Replace NavHost with QaLensNavHost (identical parameters).

```kotlin
QaLensNavHost(navController, startDestination = "home") {
    composable("home") { HomeScreen() }
    composable("checkout/{orderId}") { backStackEntry -> CheckoutScreen(backStackEntry.arguments?.getString("orderId")) }
}
// No Navigation Compose? Call QaLens.setScreen("Checkout", route) on screen changes.
```

### L3 — network and logs (wire per Q3/Q4 into the EXISTING client, do not create a second one)

```kotlin
// Q3 = (a) QaLens interceptor — add to the existing OkHttpClient.Builder
OkHttpClient.Builder()
    .addInterceptor(QaLensOkHttpInterceptor())   // metadata only: method/url/status/latency/sizes
    .build()

// Q3 = (b) Keep Chucker's inspector and QaLens metadata on the same client.
// There is no public Chucker TransactionListener; never disable QaLens interception for it.
OkHttpClient.Builder()
    .addInterceptor(ChuckerInterceptor.Builder(context)
        .redactHeaders("Authorization", "Cookie", "Set-Cookie").build())
    .addInterceptor(QaLensOkHttpInterceptor())
    .build()

// Q4 = (a) Timber — plant alongside any existing trees
Timber.plant(QaLensTimberTree())
```

### L4 — enrichment (each line unlocks one evidence type; place as commented)

```kotlin
// Application.onCreate: data sources, providers, scenarios, contracts, crash bridge
QaLens.registerDataSource("Prefs") { mapOf("theme" to prefs.theme) }
QaLens.observeDataStore("Prefs", dataStore.data) { it.toString() }
QaLens.observeRoom(db, "accounts", "orders")
QaLens.setFeatureFlagProvider { flags.snapshot() }
QaLens.registerDeepLinkScenario("Open cart", "myapp://cart", expectedRoute = "cart")
QaLens.contract("Checkout") {
    requiresTag("checkout.submit")
    requiresLabel("Submit payment")
    requiresNoFailedNetwork()
}
QaLens.bridgeCrashes(object : QaLensCrashBridge {
    override fun enrich(crash: QaLensCrash, evidence: String) { /* Sentry beforeSend / Bugsnag addOnError / Crashlytics setCustomKeys */ }
    override fun onCrash(callback: (QaLensCrash) -> Unit) {}
})

// Composables: tags, names, recomposition counters, QA moments
Modifier.qaTag("login.email.field").qaName("Submit payment").qaLensRecompose("SubmitButton")
QaLens.markMoment("checkout hangs here")
QaLens.addBookmark("suspect transition", BookmarkSeverity.BUG)

// Coroutine scope: use the provided handler so uncaught exceptions become evidence
val scope = CoroutineScope(SupervisorJob() + QaLens.coroutineExceptionHandler())
```

**Where-to-put table (report exactly what you touched):**

| File | Change |
|---|---|
| settings.gradle.kts | includeBuild or mavenLocal repository |
| app/build.gradle.kts | debug/release dependency split |
| Application class | QaLens.configure + install + L4 registrations |
| Root composable file | QaLensRoot wrapper |
| Nav host file | QaLensNavHost replacement |
| OkHttp client factory | interceptor added (or Chucker mode) |
| Logging setup | QaLensTimberTree planted |
| Key composables | qaTag / qaName / qaLensRecompose |

---

## Phase 4 — Verify (run in this order; all must pass before reporting success)

```bash
# 1. Debug build + install
./gradlew :app:assembleDebug            # (use the real module name)
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Manual (or ask the operator): floating QA bubble visible; app drawer has "QaLens Control".

# 2. THE release guarantee — a missing no-op twin fails here, never silently at runtime
./gradlew :app:compileReleaseKotlin

# 3. Webhook (only if Q6 answered a or b)
adb reverse tcp:8000 tcp:8000
# Control Room → Webhook → <URL> → "Test endpoint" → the backend answers

# 4. Optional: repo suites (only when working inside the QaLens repo)
./demo.sh test      # kotlin 115 + noop parity + web reader 32 + backend 15 + CLI
```

Verification Report table:

| Gate | Command | Result |
|---|---|---|
| debug build | assembleDebug | PASS/FAIL |
| release parity | compileReleaseKotlin | PASS/FAIL |
| overlay present | visual / adb | seen / not seen |
| webhook test | Test endpoint | response body |

---

## Phase 5 — Report back to the operator

Use this template:

```markdown
## QaLens integration complete — <app name>

### Detection report
<copy the Phase 0 table, filled>

### Applied (Q&A)
- Q1 identity: ...  Q2 level: ...  Q3 network: ...  Q4 logs: ...  Q5 bodies: ...  Q6 webhook: ...

### Files changed
<one line per file from the Where-to-put table>

### Verification
<the Phase 4 table>

### What the operator should try next
1. Reproduce a bug → tap the bubble → Overview (score + likely owner)
2. Bug Bundle → Copy Jira Bug → paste
3. Record Session → stop → Control Room → ⇪ webhook → see the verdict on the dashboard
4. Settings → QaLens Demos (sample app) to fire crashes/ANRs/bookmarks on demand
```

State honestly what was NOT verified (for example, no device attached).

---

## Edge cases and troubleshooting

- **No Compose / minSdk < 23:** the overlay SDK cannot attach. Report it; offer qalens-core only
  (pure engines) or a Compose migration. Do not claim L0 works without Compose.
- **Chucker already installed:** prefer Q3(b). Never add the QaLens interceptor twice and never
  reorder an existing Chucker interceptor.
- **Timber already planted:** QaLensTimberTree is an additional tree — plant it, keep the others.
- **JDK 25:** switch to JDK 17 (Kotlin 2.0.21 cannot parse it). The repo already sets
  `kotlin.daemon.jvmargs`.
- **Gradle wrapper download slow:** any cached Gradle 9.x binary works; the repo docs list the
  command pattern.
- **Manifest merge conflicts** (FileProvider, activity attributes): read the merged manifest
  report; the QaLens entries are debug-only and documented in integration.md.
- **`FLAG_SECURE` screens:** screenshots/recordings come out black — expected, not a crash.
- **Webhook 4xx:** do not retry (client rejection); 5xx/network errors retry 3x with backoff —
  the SDK already does this.

---

## iOS note (honest status)

The shipping SDK is Android-only. The `.sal` format, web player, CLI, and mock backend are
platform-neutral, and an iOS port is planned (the roadmap is discussed in the repo docs), but as
of this version there are NO iOS integration steps — do not invent them. If the operator asks,
say the plan exists and point them at the repo maintainers.
