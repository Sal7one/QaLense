# Lens 2.0: investigate a recorded bug with a local model

The `lens-2.0` branch adds local-model investigation to the Android archive player and modern web
player. The Python desktop embeds that same web player and proxies its model requests through an
authenticated local service. SDK capture, the existing archive format, inspection and mirroring stay
in place. This is a new investigation workflow, not a claim that every part of QaLens was rewritten.

Start with a completed `.sal`, pause near the bug and open **Insights**. Connect to a local chat model,
choose **Recorded app** or **QaLens player**, review the selected evidence, describe expected versus
actual behavior if useful, and analyze. The result separates cited observations from possible
causes, missing evidence and suggested checks.
Selecting a citation seeks to the captured moment. A request failure near buffering is a lead to
investigate; the timestamp alone cannot prove it caused the player bug.

## Choose a surface

| Surface | Entry | Model connection |
|---|---|---|
| Android | Control Room → a saved recording → player → Insights | Phone connects directly to your configured local/private model URL |
| Python desktop | `python3 tools/local-bridge/server.py --gui`, then Replay/Watch latest → Insights | Existing embedded web player calls the desktop's session-authenticated Python model service |
| Standalone web | Serve the repo, open `/web/index-v2.html`, load a recording → Insights | Browser connects to the configured local model; its CORS/network rules apply |

The classic web viewer links to the modern investigation workflow; it does not implement a second
model UI. The desktop can investigate a local recording without pairing a phone. Pairing and
recording access are needed only to capture or copy new phone evidence. Model analysis is independent
of the unauthenticated upload test backend and of scrcpy's live preview.

## Set up a model

Load an installed chat/instruction model in a local server. Enter its **base URL**, check installed
models and select one. Obvious embedding/reranking models are excluded; discovery cannot prove a
model's reasoning, JSON or vision capability. QaLens does not download weights or start a provider.

| Provider | PC base URL example | Protocol |
|---|---|---|
| LM Studio or compatible server | `http://127.0.0.1:1234/v1` | `/v1/models`, `/v1/chat/completions`, nonstreaming chat |
| Ollama | `http://127.0.0.1:11434` | `/api/tags`, `/api/chat`, nonstreaming JSON |

Both use standard chat messages and a fixed investigation prompt. Ollama's response format is
requested as JSON; other compatible servers are asked for the shared JSON report. Malformed,
oversized, timed-out or incompatible responses produce an error, not a successful AI diagnosis.
The web player's separate deterministic observations can still be useful without a model.
Protocol details: [Ollama chat](https://docs.ollama.com/api/chat),
[LM Studio chat compatibility](https://lmstudio.ai/docs/developer/openai-compat/chat-completions).

OpenAI-compatible URLs are the preferred setup on this branch; LM Studio, llama.cpp and vLLM can
be used when their installed server exposes the supported models/chat-completions contract. This
does not certify every server version, model template or optional API feature. Use the base URL
rather than a complete `/chat/completions` path.

On an Android emulator, `http://10.0.2.2:1234/v1` reaches the PC's compatible server. On a physical phone,
`localhost` means the phone. Use an authorized private-LAN endpoint, or an explicit adb reverse
for an authorized QA phone:

```sh
adb -s YOUR_QA_SERIAL reverse tcp:1234 tcp:1234
# Phone model URL: http://127.0.0.1:1234/v1
# Remove only the mapping you created when finished:
adb -s YOUR_QA_SERIAL reverse --remove tcp:1234
```

Phone HTTP still follows the host's Android network-security policy. The optional replay module adds
the normal INTERNET permission; it does not allow cleartext globally, trust arbitrary certificates
or change production manifests. If a host blocks local HTTP, its integrator must explicitly scope
any exception to the approved debug endpoint, or use a trusted HTTPS endpoint. The sample debug build
allows only localhost, 127.0.0.1 and 10.0.2.2; it is not a LAN exception for every host.

Standalone web needs the model server to allow the player's exact origin. HTTPS pages may also
block local HTTP. Use the Python desktop if browser transport policy prevents direct access. Ollama's
`OLLAMA_ORIGINS` and listener settings are documented in its [FAQ](https://docs.ollama.com/faq);
LM Studio's server controls are documented [here](https://lmstudio.ai/docs/developer/core/server).
The desktop's own shell APIs remain same-origin and session-protected; no wildcard CORS is added.

Only localhost and supported private/loopback IP literals are accepted. Public destinations, URL
credentials, queries, redirects and link-local metadata addresses are refused. An optional provider
API key stays in memory. A private address does not prove that the chosen provider runs local weights:
configure that server according to your team's data policy. QaLens cannot control provider storage,
logging or forwarding. Model suggestions never execute commands, SQL, host actions or code.

## Review the bug moment

1. Open the completed master or bug clip, seek near the symptom and pause.
2. Choose a surrounding time window. For a buffering bug, include the play action, request and
   the state after its completion. Broaden the window if those observations are outside it.
3. Describe the symptom, for example: “Play remains buffering after the request fails. Expected:
   an error with retry. Actual: loading never ends.” The question is optional.
4. Optionally enter **Expected result** and **Actual result**. These are tester reports, kept separate
   from captured observations. If expectation is absent, the report asks for confirmation.
5. Check the exact evidence and omission/coverage notes. Agree to send that selected data to the
   configured local model. Opening a recording or checking model names does not send its evidence.
6. Optionally review one saved still and explicitly include it when using a vision-capable model.
7. Analyze; follow cited moments and validate the proposed checks in the app/source.

The result begins with a QA bug report: title, selected captured action context, expected result
and actual result. **Copy QA report** exports readable Markdown; JSON preserves the full evidence
references and causes/checks. Captured steps are observed context, not proof that a bug reproduces
reliably. No actions are invented when they were not recorded. A QaLens-player investigation does
not reuse captured host-app clicks as replay-control steps. Tester statements are labeled; actual
behavior derived from model observations is labeled as an interpretation of cited evidence.

Changing recording, question, window or model settings invalidates the prior review/result. Cancel,
navigation and stale requests are guarded. Network work and Android archive/image preparation run
off the main thread. Cancellation stops the local request and rejects late results; it cannot guarantee
the provider stops generation after disconnection. Local inference can take longer than playback.

For **QaLens player**, the review also includes a snapshot of the current replay client: available
media, recording position, clock/decoder status and errors where exposed. It is explicitly labeled
`source: current-player`, with a separate observation time. This can help distinguish a replay
decoder/clock problem from a failure in the recorded app. The `player:runtime` citation displays that
reviewed snapshot; it is not an old event recorded at the current playhead. It is a point-in-time
snapshot, not a trace of every seek or proof that timeline scrolling is correct. Reproduce a player
issue, review a fresh snapshot and compare the same archive on another supported client.

An optional image is **one reviewed still from the saved recording**, not the live mirror, a video
upload or an automatic screenshot. It is reduced to at most 128 KiB and 1600 pixels per dimension.
The still's actual timestamp is retained; a video-decoder extraction may be explicitly approximate.
HD pixels are unmasked. A still can show a visible spinner or layout defect; it cannot demonstrate
motion, an audio failure, dropped playback frames or that the model understood the image.
`pixelsProvided` reports transport, while `pixelsAnalyzed` remains unverified when an image was sent.
See [Ollama vision](https://docs.ollama.com/capabilities/vision) for compatible provider behavior.

## What evidence reaches the model

The investigator reads existing redaction-aware archive tracks, keeps stable source IDs and selects
a bounded window around the bug. A small sample from each captured source is reserved before
remaining slots favor failures, marks and relevant context. Repeated error logs therefore cannot
consume every slot while independent network/crash/state evidence is available. Detailed values have
their own node/text bounds; skipped and shortened data remain explicit omissions. Prior captured
state/connectivity can provide context with their
original timestamps. No event is relabeled as occurring at the bug merely because it is nearby.

| Track | Useful evidence | Limits |
|---|---|---|
| Timeline and marks | Observed app actions, navigation, explicit bug notes | Arbitrary taps/business intent are not automatically inferred |
| Network | Method, captured URL/status/error, latency/bytes, opted-in body previews | Needs the actual host client hook; Chucker's private archive is not automatically read |
| Logs | Captured SDK/host log and event text | Retention, missed integrations and redaction limit coverage |
| State | Captured feature flags and app-owned decoded data-source maps | Does not decode encrypted files, discover arbitrary private app state or query every Room row |
| Crashes | Retained crash/stack context | Absence does not rule out an ANR, native crash or missed exception |
| Performance | Captured UI frame timings/jank/frozen-frame flags | UI frame metrics do not identify media decoder stalls |
| Connectivity and memory | Observed connectivity transitions and memory/trim samples | Samples are context, not proof of a resource/network cause |
| Analysis | Existing bounded anomaly/context chains and recording coverage | Rules are investigative leads, not a confirmed root cause |
| Optional still | One reviewed recorded frame/image | No audio or complete video analysis |
| Current player (chosen target) | Reviewed replay media/clock/error state | Separate current-client evidence; never a captured host-app event |

The context admits at most **300 items and 48,000 text characters**, plus one separately bounded
optional still. These are transport/context limits, not guaranteed model token capacity. Narrow the
window for a smaller context model. Coverage reports available capture flags and retention notes;
omissions report skipped/outside/invalid/truncated observations. Old recordings lacking coverage
metadata are treated as unknown. Empty tracks and “no matching signal” never certify a healthy app.

IDs such as `network:17` or `logs:102` refer to their **original archive-array positions**. `tMs` is
relative to `manifest.startMillis`; clip exports use their own recording clock. Images use `images:0`.
Links resolve only against the submitted evidence. Unknown citations are removed; uncited observations
are rejected, and unsupported hypotheses are marked unverified/low confidence. A valid citation
establishes provenance, not that the model's wording or causal conclusion is correct.

The optional `investigation` object distinguishes `recorded-app` and `qalens-player`. Its bounded
`runtime` snapshot uses `player:runtime`, `observedAtMillis`, `recordingPositionMs` and at most 4000
JSON characters of details inside the overall text budget. It never overwrites archive time or data.
Optional `qaContext` holds tester-reported expected/actual strings inside that same text budget.

## Send the investigation to a PC

1. Connect the Python desktop to the QA app and approve the existing phone bridge.
2. In the phone's saved-recording player, open Insights and review the selected evidence, optional
   still and tester expected/actual fields. A model connection is unnecessary for this handoff.
3. Press **Send to PC**. It queues the reviewed evidence and attaches a completed report if present.
   The handoff contains selected evidence, not the full recording; reviewed image pixels may be included.
4. With **Receive** enabled, the desktop shows the case under **Phone investigations** on Landing.
   Explicitly open it to review the QA report and copied evidence in the shared Insights player.
5. Connect a PC model and Analyze if needed. Receiving never calls a model; an incoming image needs
   renewed review/opt-in before model submission. Copy the QA Markdown or explicitly Save the case.

The phone uses a private same-package receiver and the already-approved authenticated bridge;
there is no foreground host-Activity dependency or automatic pairing. The phone queue is bounded
and expires on access; stop clears it. Unsaved PC previews clear on disconnect/device changes.
“Queued” does not mean the PC has received or saved it. The PC
keeps a bounded memory preview until Save; saved JSON is private and hash-deduplicated. The handoff
does not contain provider configuration or API-key settings. It preserves the reviewed source text;
arbitrary secrets typed into tester fields or already present in unredacted app evidence can still
be included. A malformed or oversized case is refused.

Selected evidence alone cannot replay the original video or expand unavailable capture tracks.
Imported citations show their copied evidence and real archive clock; load the original `.sal`
when video replay or more context is needed. A transferred Android player runtime keeps its
original client/observation time; it does not become current web-player state. The element inspector's
existing Send to PC continues to transfer component attributes independently.

## Give the model useful player state

For a media bug, useful host fields include playback state, loading/isPlaying, last error code,
buffered/playback position, retry policy, selected rendition and a nonidentifying media label.
QaLens cannot infer these from a spinner or from generic HTTP capture. Add them to a small,
allowlisted **cached** data source owned by the host's existing player/controller.

```kotlin
// Initialize in the host's approved QA integration owner. Reading this provider must be cheap.
val qaPlayerSnapshot = java.util.concurrent.atomic.AtomicReference<Map<String, String>>(emptyMap())
QaLens.registerDataSource("Video player", redactKeys = listOf("userId", "streamToken")) {
    qaPlayerSnapshot.get()
}

// Called by the host's existing callbacks, on the same owner/thread that reads its player.
fun updateQaPlayerState(state: String, isPlaying: Boolean, positionMs: Long, bufferedMs: Long) {
    qaPlayerSnapshot.set(mapOf(
        "state" to state,
        "isPlaying" to isPlaying.toString(),
        "positionMs" to positionMs.toString(),
        "bufferedPositionMs" to bufferedMs.toString()
    ))
    QaLens.event("Video player", "state=$state playing=$isPlaying")
}

fun reportQaPlayerError(errorCodeName: String) {
    QaLens.event("Video player", "Playback error code=$errorCodeName")
}
```

Adapt this to the actual Media3 or other player's public callbacks. Sample position at a bounded
rate instead of logging every render tick. Do not call a thread-confined player from the data-source
provider, query disk/Room, collect a Flow synchronously or expose signed media URLs/DRM credentials.
On owner disposal detach the host-owned listeners and clear the cache; this SDK has no invented
`unregisterDataSource` API. Add a debug-source/no-op integration boundary that matches your host variants.
For decoded DataStore, Room changes/allowlisted snapshots and actual OkHttp/Chucker owners, follow
[OSS integrations](OSS_INTEGRATIONS.md) and the [AI integration runbook](AI_INTEGRATION.md).

## Engineering contracts

[Evidence JSON schema](insights-evidence.schema.json), [report JSON schema](insights-report.schema.json)
and [handoff schema](insights-transfer.schema.json) describe the portable formats. Runtime checks additionally enforce cross-field timestamps, IDs,
context budgets and reviewed-image consent. Model text is treated as untrusted content, rendered as
text and never executed. Common credentials are additionally masked when preparing text; this is
not a substitute for host redaction or review of customer/business data.

The desktop offers these session-authenticated endpoints independently of phone pairing:

| Method and route | Request | Result |
|---|---|---|
| `POST /api/insights/models` | `{config:{baseUrl,protocol,apiKey?}}` | Detected protocol and filtered model IDs |
| `POST /api/insights/analyze` | `{config:{baseUrl,protocol,model,apiKey?},bundle,includeImage?:true}` | Async job |
| `GET /api/insights/jobs?id=ID` | Owned job ID | queued/running/done/error/cancelled state; report on success |
| `POST /api/insights/cancel` | `{id}` | Cancellation state |
| `GET /api/investigations/inbox` | Current approved phone connection | Validated memory previews; acknowledged phone handoffs |
| `GET /api/investigations/previews` | Desktop session | Pending cases |
| `GET /api/investigations/saved` | Desktop session | Saved case metadata |
| `GET /api/investigations/document?hash=HASH` | Case hash | Pending or saved case |
| `POST /api/investigations/save` | `{hash}` | Explicit private JSON save/dedup |

Protocol is `auto`, `openai` or `ollama`. The service allows one active analysis, two outstanding
workers and two discovery calls; it retains at most eight jobs in memory. Terminal jobs expire
after ten minutes when checked by job access or a new analysis, and capacity can evict them sooner.
Provider responses are bounded and requests have deadlines. Analysis uses a separate 512 KiB request limit;
other existing shell endpoints retain their existing limits, including the separate file/import budgets.
Model calls do not hold the shared phone/
mirror mutex. The iframe bridge checks exact parent/source/origin and correlates requests; unrelated
frames cannot drive these APIs. Evidence, reports, API keys and stills are not saved by this analysis
service; copying/exporting results is an explicit user action.

## Verification and remaining acceptance

Run the portable commands in [CONTRIBUTING](../CONTRIBUTING.md#local-model-insights-checks).
`tools/insights-tests/recording.json` represents a synthetic HTTP 503 plus buffering/state observations.
Its archive generator and local HTTP fixture exercise real archive loading, both protocols, desktop
authentication, output validation, stable citations, optional-image transport and log-flood bounds.
The fixture is **not a language or vision model** and its canned response cannot evaluate reasoning.

Actual LLM/VLM quality needs an installed suitable model and an evaluation set with known causes,
ambiguous cases and missing evidence. Test the same synthetic recording on phone, standalone web
and embedded desktop; test a physical/company host separately. Browser rendering, accessibility,
provider-specific context/vision behavior and host/OEM capture acceptance have their own checks.
Current executed evidence is recorded in [HANDOVER](../HANDOVER.md), not inferred from a green build.
