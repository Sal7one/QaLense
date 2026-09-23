# Floating overlay design tokens

How the QaLens floating overlay is coloured, sized and spaced, and the evidence behind
those choices. The source of truth is `QaLensTokens.kt` and this document; the token layer
currently covers the floating bubble, tester quick-actions sheet, and inspect/tag canvases.

## The problem this solves

The overlay colours were hard-coded per file:

| Surface | File | Was |
|---|---|---|
| QA bubble | `QaLensOverlay.kt` | `#111827` disc, white ring, amber `#FFC107` for inspect |
| Inspector panel | `QaLensInspectorPanel.kt` | `#E6111827` panel, `#60A5FA` accent, `#FBBF24` warn |
| Tester quick-actions sheet | `QaLensMinimalPanel.kt` | tokenized panel surfaces with semantic action colors |
| Canvases | `QaLensOverlay.kt` | `#2196F3` / `#00C853` / `#E53935` / `#FFC107` |
| Control Room | `QaLensControlActivity.kt` | `#0B0F17`, `#151B26`, `#60A5FA` |

Eight separate palettes, and no host app could influence any of them. Two consequences were
visible on the sample app: the floating layer read as pasted on, and `#E53935` meant
*accessibility warning* in `InspectCanvas` but *untagged interactive component* in
`TagCanvas` — the same red for two different messages.

## One decision

`QaLensOverlayColors` exposes two palettes and one selector:

```kotlin
qaLensColorsFor(context)              // reads Configuration.uiMode
schemeForConfiguration(configuration) // the decision, testable without a Context
qaLensColorsForScheme(scheme)         // scheme -> palette
```

A **light host** gets the dark overlay panel — the ink the product already used, now one
family instead of eight ad-hoc values. A **dark host** gets a light overlay panel. Either
way the floating layer separates from the app by contrast rather than by a drop shadow.

The light panel is **opaque, not translucent**. Over a dark app a translucent panel has
almost no contrast, so the overlay would vanish at exactly the moment it is needed.

### Why the configuration, not `isSystemInDarkTheme()`

The overlay is attached as a decor-level `ComposeView` by `QaLensActivityInstaller`, so it
sits *outside* the host's Material theme and cannot read `MaterialTheme.colorScheme`. The
configuration night mask is the honest signal available at that layer, and it is what the
host app itself follows when it flips themes. `schemeForConfiguration` is split out so the
branch is unit-tested rather than assumed.

## Contrast is verified, not claimed

Every colour was measured against the surface it is used on. The unit test
`QaLensTokensTest` recomputes WCAG 2.x contrast and fails the build if any pair drops below
AA 4.5:1, so a future colour edit cannot silently break this.

| Token | Light host | worst ratio | Used on |
|---|---|---|---|
| `fg` | `#EEF2F6` | 16.6:1 | primary text |
| `fg2` | `#AEB9C6` | 9.4:1 | secondary text |
| `fg3` | `#7D8898` | 4.6:1 | labels, units |
| `accent` | `#4FC4D0` | 8.0:1 | the one colour QaLens owns |
| `ok` | `#57BF7E` | 7.2:1 | healthy, passed |
| `info` | `#6BA8E8` | 6.6:1 | neutral fact |
| `warn` | `#E0A93C` | 7.8:1 | degraded, slow |
| `err` | `#E87575` | 5.7:1 | failed, a11y issue |
| `critSolid` | `#8E1B1B` | 8.2:1 | white text on the fill |

| Token | Dark host | worst ratio |
|---|---|---|
| `fg` | `#14161A` | 18.1:1 |
| `fg2` | `#4A5462` | 7.7:1 |
| `fg3` | `#5F6A7A` | 5.0:1 |
| `accent` | `#0E6F7A` | 5.3:1 |
| `ok` | `#12703B` | 5.6:1 |
| `info` | `#1B5AA0` | 6.3:1 |
| `warn` | `#8A5300` | 5.7:1 |
| `err` | `#B02020` | 6.2:1 |

### A mistake worth recording

The first draft of this design quoted 4.6–5.4:1 for the signal colours. They actually
measured **2.2–3.8:1** against the dark panel, because they had been picked against a light
surface and then drawn on a dark one — `#A32020` on `#0E131A` is 2.47:1. The values were
recomputed for the surface they are actually used on. If you change a colour, recompute it;
do not copy a ratio forward, and do not copy a colour across the two schemes.

## Non-colour signal carriers

Severity is never carried by colour alone, so the overlay still reads in greyscale and for a
colour-blind tester:

- metric tiles carry a tint **and** a 3px left rule **and** the label
- canvas outlines pair a colour with a shape: tagged = plain box, untagged = box + dot,
  warning = box + dot, selected = thicker rule
- chips state their meaning as text ("4 warnings"), never as an icon alone

## Sizing and spacing

`QaLensDimens` replaces the ad-hoc `6.dp` / `8.dp` / `10.dp` spread with a 4dp scale
(`s1`…`s6`), one radius family (`rChip`, `rXs`…`rXl`), and `touchMin = 48.dp`.

`touchMin` is the Android floor for new controls. Existing inspector and Control Room controls
still need a separate sizing review; the token declaration alone does not resize them.

`rowTimeColumn = 46.dp` fixes the leading column width so timestamps and values line up
vertically down a track — the property that makes evidence scannable.

## Type

`QaLensType` is the `sp` scale. The rule that matters: **any value that is compared or
scanned uses the monospace family with tabular figures** — timestamps, durations, status
codes, byte counts, percentages, p95. Proportional type is for prose and labels only.

## Adoption status

Applied:

- `QaLensTokens.kt` — the token layer (new)
- `QaLensTokensTest.kt` — contrast and scheme regression tests (new)
- `QaLensOverlay.kt` — bubble, inspect canvas, tag canvas, panel scrim, tag chip and the
  tag-mode legend now read from tokens. The bubble keeps its original crisp white ring; a
  softened ring measured worse against the cream host and read as a smudge.
- `QaLensMinimalPanel.kt` — the simplified tester sheet uses the same surfaces and status colors.

Not yet migrated, in priority order:

1. `QaLensInspectorPanel.kt` — the largest remaining surface to migrate and simplify.
2. `QaLensControlActivity.kt` — developer setup and configuration.
3. `QaLensSystemChip.kt` — the recording chip.

Until those land, the inspector, Control Room and recording chip still use their own colors and
sizing. Do not claim the entire QaLens UI is host-adaptive.
