# QaLens Architecture

## Design principle

QaLens must make QA bug reports better without coupling the app to a heavy automation stack.
Debug-only by construction: release builds link `qalens-noop` (identical public API, zero behavior).

## Component map (service-first since the 2026-06 overhaul)

```text
qalens-core            pure Kotlin/JVM — models, config, redaction, rules, engines (timeline,
                       repro, classifier, network health, contracts, scenarios), .sal encoders
                       (QaLensSalFormat) and the AI digest layer (QaLensAnalysis).
qalens-android         device/build info, shake detector, notification, FileProvider,
                       persisted prefs (QaLensPrefs).
qalens-compose         the in-app layer:
                       ├─ QaLens                  facade + StateFlow state
                       ├─ QaLensActivityInstaller lifecycle hooks, overlay injection, healing,
                       │                          notification-permission request, armed recordings
                       ├─ QaLensOverlay           bubble / panel / inspect canvas / TAG canvas /
                       │                          watch HUD / in-window REC chip
                       ├─ QaLensInspectorPanel    the tabbed panel
                       ├─ QaLensSessionRecorder   .sal recorder (frames or MediaProjection video)
                       ├─ QaLensSystemChip        floating stop chip (draw-over-apps, optional)
                       ├─ QaLensControlService    manifest-declared command service — ALL
                       │                          notification actions route here (never dies
                       │                          with an activity)
                       ├─ QaLensControlActivity   "Control Room": own task + launcher icon;
                       │                          recording controls, panic restore, overlay
                       │                          kill-switch, recordings manager, webhook
                       └─ QaLensWebhook           multipart .sal upload to an analysis backend
qalens-navigation-compose  QaLensNavHost route tracking
qalens-replay          on-device .sal player (own task, no launcher icon; opened from the
                       Control Room or a shared file)
qalens-noop            release-safe mirror of every public API
web/                   offline web player + sal_report CLI + sample generator
```

### Task model (load-bearing — do not regress)

`QaLensControlActivity` and `QaLensPlayerActivity` run in their **own tasks**
(`taskAffinity` + `singleTask`). History: when the player shared the app's task, both launcher
icons resumed whatever was on top ("two icons, same activity" bug). The installer's
`isInternal()` list keeps the overlay out of QaLens-owned screens.

### Recording stop controls (layered — never strand QA)

1. Floating system chip (`TYPE_APPLICATION_OVERLAY`, when granted) — own window, never in frames.
2. In-window REC chip rendered by the overlay (fallback; hidden per-frame during PixelCopy).
3. Notification action → `QaLensControlService` (manifest-declared, survives activity churn).
4. Shake = emergency stop while recording.
5. Control Room / panic restore (also discards stuck sessions and re-attaches the overlay).

## Inspection data sources

1. **Manual metadata** via `Modifier.qaTag` and `Modifier.qaName`.
2. **Compose semantics** via debug-only reflection into `AndroidComposeView.getSemanticsOwner()` —
   contained in one reader object; acceptable because release is a no-op.
3. **Opt-in capture**: OkHttp interceptor (network), Timber tree (logs), feature-flag provider,
   DataStore/Room observers, screen contracts, deep-link scenarios.

## The `.sal` artifact

One ZIP per QA session: media (frames or video) + synced JSON tracks + `analysis.json`
(precomputed digest, schema `qalens-analysis/1`) + `for_ai.md` (the archive explains itself to
any AI). Spec and backlog: [`replay_backlog.md`](replay_backlog.md).

## Trust boundaries — what QaLens does and does not do

- Debug builds only; release is a hard no-op.
- **Nothing leaves the device unless QA explicitly acts**: the share sheet, or the Control Room
  webhook (off until a URL is configured by the QA engineer; auth header + endpoint are theirs).
- Every export path (clipboard, screenshot, `.sal`, webhook) runs through `QaLensRedactor`
  (JWTs, auth headers, cookies, emails, cards, phones, long IDs).
- Network bodies are **not** captured — only method/url(redacted)/status/latency/sizes.
- "Draw over other apps" is **optional** (floating stop chip); everything works without it.
- No root, no AccessibilityService, no global tap capture — the timeline never fabricates
  events it didn't observe.

## Where the old phase backlog went

Phases 1–3 of the original plan shipped (panel, reports, network/flags/state adapters), the
replay/AI work is tracked in [`replay_backlog.md`](replay_backlog.md), and stress tooling /
baseline diffs remain future ideas.
