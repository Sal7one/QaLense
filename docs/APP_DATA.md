# Control Room: SQL and app data

Open **Control Room → Database · Raw SQL** to query this app's SQLite files, or **App Data · Prefs & DataStore** to
inspect read-only fields exposed by the host. These tools belong to the SDK; they work in a
consuming QA/debug app without the sample, desktop or backend. Production uses `qalens-noop`.

## SQL controls

Choose a database from the full list; **Rescan** discovers files created since opening the card.
Long database names remain available in the picker. **List tables** runs a `sqlite_master` read.
The SQL field, **Run query**, query name and **Save query** have separate space; actions have
48 dp minimum height. Saved queries show their name/SQL above equally sized **Run / Delete**.
Control Room respects system bars and the keyboard; button groups wrap on narrow displays.

Reads run off main, return at most 100 rows and retain 80-character cell previews. The table
initially offers 12 rows; **Show all N rows** exposes the retained rows, with lazy vertical
scrolling inside results and horizontal column scrolling. The viewport is at most 260 dp high;
the UI displays at most 30 columns and explains that limit. Select fewer columns or paginate
explicitly in SQL when investigating larger data. Every run resets the table preview.

**Cancel query** cancels a running read through Android's cancellation signal. SQLite writes
can already have committed before cancellation; Cancel is not undo. Running another query is
blocked until the current worker finishes. Disabling QaLens or leaving the card requests
cancellation. Query failures remain visible, and SQL breadcrumbs join the evidence timeline.
Raw SQL results are app sandbox data; normal SDK text rules do not mask SQL cells automatically.
Use disposable or approved data, and do not paste credentials into saved SQL or public artifacts.

## Connect useful DataStore values

QaLens cannot infer the schema or decrypt an arbitrary backing file. Feed the **existing
app-owned decoded Flow** to `observeDataStoreValues`; it publishes allowed fields to **Live app
values**, reports, desktop cached snapshots and recording state. No new DataStore instance,
serializer, file reader or encryption workaround is needed.

For Preferences DataStore, use the real instance from Application/DI and existing keys:

```kotlin
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.qalens.QaLens

val themeKey = stringPreferencesKey("theme")
val offlineKey = booleanPreferencesKey("offline_mode")

// Run once from the existing store's owner; dataStore is the host's real instance.
QaLens.observeDataStoreValues("Settings", dataStore.data) { prefs ->
    mapOf(
        "theme" to (prefs[themeKey] ?: "system"),
        "offlineMode" to (prefs[offlineKey] ?: false).toString()
    )
}
// When that owner ends (not when a tester closes Control Room):
QaLens.stopObservingDataStore("Settings")
```

Proto DataStore and encrypted/custom stores use the same generic API: map fields from the
decoded type emitted by their existing Flow. The app owns decryption and chooses what QA may
see. Never expose the whole preferences object, authentication material or arbitrary user data.
The sample's regular settings are an in-memory Flow; the focused device test uses real
Preferences DataStore. Neither fixture proves another app's serializer or encryption integration.

`snapshot` runs on a worker. The SDK stores a cache and reads that cache during analysis;
there is no synchronous store I/O on main. Return a small map, keep the mapper cheap, and avoid
changing the host's collector, coroutine scope or store lifetime. The no-op API does not collect
the Flow or evaluate the mapper.

### Privacy and lifetime

- First emission populates values without a change event. Later emissions record only a count,
  such as `Settings changed: 2 exposed fields`; values join the redacted state track, not that label.
- Up to 100 fields are retained, with 200-character keys and 2,048-character value previews plus
  a truncation marker. Redaction happens before truncation. Credential-like keys are masked.
- Optional `redactKeys`, `redactPatterns` and `redactAll` apply at registration. Current global
  rules also apply to field names and values before display/recording. Already masked/truncated
  cached values cannot recover their original content by relaxing rules; a later emission is needed.
- Reusing an observer name replaces that binding. Use one DataStore observation API per name.
  Disable pauses collection and hides values; re-enable reads initial state again without a change
  event. Stop removes only that observer's owned preview, preserving a newer manual provider.
- Completion displays **Source ended · last values**; failure displays **Source unavailable · last
  values** and a surfaced SDK error. Neither means continued updates. Last-received time is not a
  freshness guarantee, and stopping QaLens never closes the host's store/scope.

The older `observeDataStore(name, flow) { "settings updated" }` remains an event-only hook that
skips the initial read. Use it when values are unnecessary. A cached `registerDataSource` provider
also remains supported; it runs on main and must perform no disk/database/network work. Those
manual providers are process-lived. See [OSS integrations](OSS_INTEGRATIONS.md).

## Use App data

**Live app values** lists registered sources and update status. Tap a source to expand its fields;
search source names, keys or redacted values. Search expands matching fields. **Refresh app data**
refreshes analysis from cached providers; it does not force a DataStore emission or read/decrypt a
file. The view offers up to 30 sources and retains up to 100 fields per source; at most 100 expanded
field previews render at once, with a refine-search hint if more match. This is inspection, not
arbitrary settings editing. No connected values produces an actionable integration explanation.

**Show storage files** is optional. Preferences files can expose a bounded redacted snapshot;
SDK `qalens*` configuration files are excluded. Recognized AndroidX encrypted preference envelopes
show guidance to connect decoded values instead of ciphertext. Other formats/encryption schemes
cannot be identified reliably; the host value hook is the supported integration path.
DataStore backing files show names/sizes only and explicitly describe that coverage. File lists
show at most 50 entries each. Stored values and file metadata are distinct sources of evidence.

## Inspect from the desktop

Phone approval now discloses decoded fields, preference/SQLite reads and shared saved queries.
**Landing → Live diagnostics → App values** reads the same cached values/status; it never calls a
provider or opens a DataStore on demand. Follow/Pause, source/key/value search, ten memory-only pins
and comparison baselines help spot changes while using the mirror. Missing fields are explicitly
unavailable in that bounded snapshot, not claimed to be deleted. Privacy/config or connection
changes clear prior comparisons. Desktop returns at most 30 sources / 300 fields / 100,000 field
characters with omission counts. No new public hook, no-op subscription or recording format is added.

Binary `.preferences_pb` does not by itself mean encryption. An IDE plugin may know that schema;
the supported integration is the existing decoded host Flow shown above. Proto/custom/encrypted
DataStore follows the same mapper contract. **Data tools → Show storage files** offers metadata,
explicit redacted ordinary preference snapshots and recognized encrypted-envelope guidance.
It excludes SDK config and never accepts an arbitrary file path or runs a second DataStore.

**Data tools → SQL workbench** queries the app's own standard SQLite/Room files through a separate
read-only connection; it cannot use Control Room's writes. IO jobs expose cancellation and a
ten-second cancellation watchdog without occupying the device bridge socket. Rows/cells/columns
are bounded and credential-like column names plus current rules are masked before truncation;
SQL aliases and ordinary business data still need host policy. Saved read queries share the existing
phone list; enter Control Room or Rescan to refresh it. See the
[desktop data contract](../tools/local-bridge/README.md#sql-workbench-and-storage) for limits and API.

## Verify in a consuming app

Change an allowed setting through the app's real UI or existing state owner. With Control Room
foreground, verify the decoded field updates, search works and credentials stay hidden. Record a
short session and inspect the redacted state/change label; disable/re-enable and dispose the hook.
Test SQL selection, saved Run/Delete and cancellation on approved data at the host's smallest
supported display, enlarged text and navigation settings. Check the real production graph/manifest.

The focused disposable API 36 runner is documented under
[contributor device checks](../CONTRIBUTING.md#focused-device-checks). Its SQL fixture reaches the
sixth long-named database, all retained rows, saved queries, errors and cancellation. Its real
DataStore fixture checks initial state, foreground updates, privacy changes, disable/re-enable,
stop, finite Flow completion, mapper failure and manual-provider ownership. Storage checks use
synthetic plain preferences and an envelope marker; they do not implement/test cryptography.
Physical phones, TalkBack, other serializers/OS versions and actual consuming apps need their
own acceptance. Recording schema and capture consent behavior are unchanged.
