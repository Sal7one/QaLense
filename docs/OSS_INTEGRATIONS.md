# OSS integrations and supported capture paths

QaLens adds session context and portable evidence to tools people already use. Optional libraries
stay host-owned: the SDK does not force Chucker, OkHttp, Timber, Room or a crash vendor transitively.
`sample-app/SampleOssTools.kt` is a compiled example, including its release no-op configuration.

## Chucker: supported coexistence

```kotlin
// app/build.gradle.kts — this repository's Kotlin 2.0.21 compatibility baseline
debugImplementation("com.github.chuckerteam.chucker:library:4.1.0")
releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.1.0")
```

```kotlin
val client = OkHttpClient.Builder()
    .addInterceptor(ChuckerInterceptor.Builder(context)
        .redactHeaders("Authorization", "Cookie", "Set-Cookie")
        .build())
    .addInterceptor(QaLensOkHttpInterceptor())
    .build()
```

Chucker provides full request/body inspection under its own privacy settings. QaLens defaults to
metadata only. Configure both tools' privacy settings separately; QaLens does not redact Chucker's
stored data. Place the QaLens application interceptor on the client that actually executes traffic.
Redirects/retries produce one completed-call observation with the final status; separate network
attempts are not represented as independent calls.

The previous `networkFromChucker=true` integration was invalid: `TransactionListener`,
`ChuckerTransaction`, `getCollector` and `Chucker.launch` are not the public APIs it claimed.
Keep the flag false. Setting it now emits a migration note and leaves QaLens capture enabled.
The launcher calls public `Chucker.getLaunchIntent(context)` and checks `Chucker.isOp`, so the
Chucker release no-op is not mistaken for an active inspector. Overview exposes **Open Chucker**
when the active library is present.

Sources: [Chucker 4.1 public launcher](https://github.com/ChuckerTeam/chucker/blob/4.1.0/library/src/main/kotlin/com/chuckerteam/chucker/api/Chucker.kt),
[4.2 collector API](https://github.com/ChuckerTeam/chucker/blob/4.2.0/library/src/main/kotlin/com/chuckerteam/chucker/api/ChuckerCollector.kt),
[4.3.1 launcher](https://github.com/ChuckerTeam/chucker/blob/4.3.1/library/src/main/kotlin/com/chuckerteam/chucker/api/Chucker.kt).
The launcher contract is present in those sources; the executed sample baseline is 4.1.0.
Chucker 4.2.0's published Kotlin metadata is 2.2.0 and failed with this repo's Kotlin 2.0 compiler.
Do not suppress metadata compatibility checks: keep host-compatible versions or upgrade the host
Kotlin/AGP toolchain together. There is no runtime-validation claim for 4.2/4.3 here.

## Other HTTP stacks and custom tooling

`QaLensNetworkSink` lives in pure Kotlin core. Create one per integration, keep the name short and
stable, and report a completed observation from the transport's success/error callback:

```kotlin
val sink = QaLens.networkSink("Custom transport")

fun requestCompleted(method: String, url: String, status: Int, elapsedMillis: Long, error: String?) {
    sink.record(NetworkEvent(
        method = method,
        url = url,
        status = status,
        latencyMs = elapsedMillis,
        error = error
    ))
}
```

This is the adapter seam for Ktor, Cronet, Apollo or an existing tracing callback. It is not an
installed native plugin for those libraries; connect it to the callback your version exposes.
Record each completed request once. If Ktor/Apollo uses an already instrumented OkHttp client,
use the interceptor alone to avoid double reporting. Never consume a one-shot body to feed a sink.
Provide previews only from text the host already owns; captureNetworkBodies must also be enabled.

All sink and direct `logNetwork` events pass the same policy: master/network capture flags,
query-value removal, metadata/error redaction, optional bounded body previews and nonnegative
size/duration normalization. Default capture now discards externally supplied bodies too.
Body previews are bounded to 65,536 characters plus a truncation marker at this text boundary;
OkHttp additionally limits bytes read. Release sinks accept calls and retain nothing.

QaLens automatically deduplicates accidental repeated QaLensOkHttpInterceptor instances within
one OkHttp Call. Reusing a Request in a later Call still records it. It cannot deduplicate arbitrary
independent adapters without stable upstream request identity.

## Timber and crash reporters

Plant `QaLensTimberTree()` beside existing Timber trees. `captureLogs=false` gates automatic
Timber forwarding; explicit QaLens logs/events remain opt-in while the SDK is enabled. The master
`enabled=false` switch now gates explicit logs and all network adapter input too.

For crashes caught by another reporter, use `QaLens.reportCrash(QaLensCrash(...))`, or register a
`QaLensCrashBridge` with an `onCrash` callback. Inbound crashes are bounded/redacted before display
and recording, and are never echoed through `enrich` back to the vendor. Both bridge directions
are optional. QaLens-to-vendor enrichment uses redacted metadata and remains best-effort on main;
it cannot guarantee execution before an uncaught exception terminates the process. Attach required
extras in the vendor's own synchronous before-send hook. Do not call the vendor's capture/report
method from enrichment: it should attach context, not create another crash report.

## Diagnose wiring

Call `QaLens.integrationReport()` or use Overview → **Copy integration check**. The report lists
capture settings, declared sources and dashboard counts without copying request contents. A source
is declared when its interceptor/sink is constructed; this alone cannot prove it is on the correct
client. Perform a request, inspect its row, and record a short session. The archive includes declared
names in `analysis.json.coverage.networkSources`. Source names are capped at 16 per process.

## Regression checks

- Pure tests cover capture gating, privacy, adapter limits, diagnostics and bounded crash metadata.
- Real OkHttp/MockWebServer tests cover body preservation, duplicate installation, redirects,
  reused requests, concurrent calls, config changes and observer/transport failures.
- The separate consumer fixture compiles the same public API against debug and release artifacts
  through Maven-style coordinates plus `includeBuild`; release isolation is checked independently.
- The device runner in [recording retention](RECORDING_RETENTION.md) also checks the real Chucker
  launcher intent, a loopback HTTP exchange through Chucker plus duplicated QaLens interceptors,
  adapter gating/redaction and absence of crash-vendor echo. It does not send data externally.

## Client safety defaults

See [client audit fixes](CLIENT_SAFETY_FIXES.md) for screenshot masking/private storage,
full-display video opt-in, runtime shutdown and coroutine exception delivery. Opt-in OkHttp response
previews read only known-length bodies of at most 64 KiB; SSE, larger and unknown-length responses
remain metadata-only. QaLens never opens those response sources just to generate a preview.
