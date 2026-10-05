# Mobile QA overlay

The overlay is for capturing and investigating a test session. Open the floating QA bubble;
Close or system Back dismisses quick actions. It is part of the SDK and works in a consuming
app, without sample-app menus. Configuration and saved recordings live in **Control Room**.

## Start with a task

| Action | What happens |
|---|---|
| Record a session | Start masked sampled frames and observed activity. The overlay hides during recording; the REC control provides Stop and recent clips. |
| Screenshot | Save an annotated screenshot of the current view. |
| Mark a bug | Add a timestamped starred breadcrumb and save a screenshot. This does not file a ticket or describe the defect for you. |
| Inspect elements | Select a visible Compose element on the app; read attributes, tags, supported actions and copy selectors. |
| Review evidence | Browse the five evidence views below; copy a report or observed steps. |
| Connect to PC | See the desktop connection instructions. Use the desktop's phone/app discovery and approve its request on this phone. Manual pairing is a separate optional section. |
| Control Room | Replay/share/delete saved recordings; configure HD, team sharing, profiles, macros and explicit data tools. |

**Send latest session** appears only after a team endpoint is configured and a recording exists.
It reports sending, success and failure states. Captures save locally first; explicit sharing,
configured retry uploads and opted-in desktop collection have their own destinations. Nothing
about opening the overlay grants desktop access or changes capture privacy.

The header's Close remains visible while the action list scrolls. Inspection has a movable dock;
two-finger drags scroll the host app while single taps select elements. **Done** or system Back
exits inspection and returns input to the app. **Search selectors & tags**
in that dock searches tags, text, roles and supported actions. **Actions & XPath selectors** on a
selected element shows copyable suggestions; **Send to PC** is an optional captured attribute
transfer. Live phone/browser selection is linked by the desktop's separate setting. See the
[desktop contract](../tools/local-bridge/README.md#search-and-selectors).

## Review observed evidence

| View | Useful evidence |
|---|---|
| Activity | Recent observed navigation, business events, logs and requests, newest first. Captured crash/ANR summaries have expandable stacks; Copy bug report includes retained failure stacks. Copy observed steps exports the observed sequence. |
| Network | Retained requests, status/error and latency; concrete failed/slow counts and p95. Declared or missing hooks affect coverage. |
| Logs | Bounded live dashboard history, search/filtering and grouped observations. |
| Elements | Search the redacted visible Compose tree and select a component on the app; Show tags displays automation tags. View checks lists concrete rule findings and app-registered contract results. |
| Device | Installed app/build/environment/device details, declared sources, integration check, observed performance and registered app data. Chucker opens only when available. |

Search on Activity/Network/Logs searches the observed tracks and components together; choosing
a component opens its inspection details on the app. Choosing a different view clears that query.
Element filters are collapsed initially; active filters keep their count when collapsed. Expanded
options scroll with results so they cannot consume the whole result viewport. Back from
View checks returns to element search. Elsewhere Back clears an active evidence query first, then
returns to quick actions. Back again closes the overlay. The **‹ Actions** control also returns directly. Host-provided
`QaLensTabProvider` views remain available after the five built-ins.

Automatic element findings can miss problems; no findings is not a clean bill of health. Retained
counts describe available evidence, not the whole app or all traffic. Crash previews reapply the
current redaction rules on a worker. Reports and search/filter computations stay off main;
lists render lazily. See [integration](../integration.md) for actual hooks and privacy settings.

## Removed from the overlay

The October 2026 cleanup removed the old Screen Health tab and score grid, likely-owner cards,
completeness percentage, separate bookmark editor, duplicate Bug Bundle/report buttons, separate
Navigation/Accessibility/Automation Tags/Inspect tabs, transparency slider, title-tap docking,
Lock/Watch shortcut, recent macro drawer, arbitrary deep-link launcher and duplicate recording
management. These were competing controls or broad claims without a clear tester task.

The underlying public APIs, `.sal` tracks and release/no-op boundary remain supported. In
particular, a developer `addBookmark()` is a timestamped annotation retained in `marks.json`;
the UI no longer offers that second annotation editor. Screen scores/session summaries still
exist for API/report compatibility, but are not presented as an overlay health verdict. Watch
remains an explicit API mode. Named macros run from Control Room after the host app resumes;
their UI/capture actions must not target the SDK control Activity.

`panelMode="minimal"` starts on quick actions; `"full"` starts on Review evidence. Existing
`.appsal` files retain those keys. Capture/evidence panels are opaque; the existing opacity
setting applies to the optional Watch HUD. Full-height evidence is not docked to half the screen.

## Verification

Use the [contributor focused modes](../CONTRIBUTING.md#focused-device-checks): `workflowOnly`
checks actual navigation, searchable/copyable evidence, all built-in views, a host extension,
screenshots/marks/recording, Control Room macro execution, upload and replay. `selectorsOnly`
checks real search/selection/XPath copy; `pcUiOnly` checks manual pairing/rotation/stop through SDK
UI; `overlayLoadOnly` checks evidence views while logs/network keep arriving. Require each
mode's `OK:` output and no `FAIL:`; an adb exit code alone is insufficient.

Workflow previews/failure images are synthetic sample screenshots in private cache, not public
recordings or shipping assets. Current executed devices/font sizes and remaining physical-phone,
TalkBack and host-app limits are recorded in [Android verification](ANDROID_VERIFICATION.md).
