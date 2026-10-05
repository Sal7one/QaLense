package com.qalens.sample

import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.qalens.QaLens
import com.qalens.QaLensControlActivity
import com.qalens.QaLensDataEvents
import com.qalens.android.QaLensAppSal
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import java.io.File

/** Actual SQL controls and app-owned decoded Preferences DataStore; disposable sample only. */
internal class AppDataUiChecks(private val test: Instrumentation) {
    private val context = test.targetContext
    private val source = "QA settings fixture"
    private fun changes() = QaLens.state.value.events.count { it.tag == QaLensDataEvents.DATASTORE && it.message.startsWith("$source changed:") }

    fun run() {
        val config = QaLens.config.value
        val appSal = QaLensAppSal.current(context)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val databases = (0..5).map { "qalens-ui-fixture-$it-long-database-name.db" }
        val file = File(context.filesDir, "datastore/qalens-ui-fixture.preferences_pb").apply { parentFile!!.mkdirs() }
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val theme = stringPreferencesKey("theme")
        val token = stringPreferencesKey("accessToken")
        val plainPrefs = "app-qa-ui-plain"
        val encryptedPrefs = "app-qa-ui-encrypted"
        try {
            test.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            test.runOnMainSync { QaLens.configure { enabled = true }; QaLens.closePanel() }
            databases.forEach { name -> context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE IF NOT EXISTS notes (id INTEGER, label TEXT)")
                db.execSQL("DELETE FROM notes")
                repeat(30) { db.execSQL("INSERT INTO notes VALUES (?, ?)", arrayOf(it, "fixture-row-$it")) }
            } }
            runBlocking { store.edit { it[theme] = "day"; it[token] = "never-display-fixture-value" } }
            context.getSharedPreferences(plainPrefs, 0).edit().putString("theme", "prefs-day")
                .putString("password", "never-display-prefs-secret")
                .putString("description", "x".repeat(3_000) + "private-tail").commit()
            context.getSharedPreferences(encryptedPrefs, 0).edit()
                .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", "synthetic-envelope")
                .putString("cipher-key", "never-display-cipher-value").commit()
            QaLens.observeDataStoreValues(source, store.data) { prefs ->
                mapOf("theme" to prefs[theme].orEmpty(), "accessToken" to prefs[token].orEmpty())
            }
            await("Initial decoded values not exposed") { QaLens.state.value.dataSources[source]?.get("theme") == "day" }
            check(QaLens.state.value.dataSources[source]?.get("accessToken") == "[REDACTED]")
            check(changes() == 0) { "Initial DataStore read was reported as a change" }
            test.startActivitySync(Intent(context, QaLensControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

            click("Choose database")
            click(databases.last()) // The old four-chip picker could never reach this database.
            set("SQL query", "SELECT id, label FROM notes ORDER BY id")
            set("Query name", "UI fixture query")
            checkButton("Run query"); checkButton("Save query")
            seek("Run query").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            screenshot("qalens-control-sql-after.png")
            click("Run query")
            seek("12 row previews · 30 rows available", contains = true)
            click("Show all 30 rows")
            seek("fixture-row-29")
            click("Save query")
            check(QaLensAppSal.queries(context).any { it.name == "UI fixture query" && it.db == databases.last() })
            click("Run saved query UI fixture query")
            seek("12 row previews · 30 rows available", contains = true)
            click("Delete saved query UI fixture query")
            check(QaLensAppSal.queries(context).none { it.name == "UI fixture query" })
            click("List tables"); seek("notes")
            set("SQL query", "SELECT missing_column FROM notes")
            click("Run query"); seek("Query failed:", contains = true)
            set("SQL query", "WITH RECURSIVE seq(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM seq WHERE n<1000000000) SELECT SUM(n) FROM seq")
            click("Run query"); checkButton("Cancel query"); click("Cancel query")
            await("Cancel did not restore Run") { find("Cancel query") == null }
            set("SQL query", "SELECT 42 AS answer")
            click("Run query"); seek("42")

            set("Search app data", "theme")
            seek("day")
            runBlocking { store.edit { it[theme] = "night" } }
            seek("night") // Control Room is foreground; this must not depend on host resuming.
            await("Change event missing") { changes() == 1 }
            check(QaLens.state.value.events.none { it.message.contains("never-display-fixture-value") })
            set("Search app data", "accessToken")
            seek("[REDACTED]")
            check(find("never-display-fixture-value") == null)
            set("Search app data", "")
            if (find("night") == null) click("App values source $source")
            seek("night"); seek("[REDACTED]")
            seek("App values source $source").performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            screenshot("qalens-control-app-data-after.png")
            set("Search app data", "theme")
            test.runOnMainSync { QaLens.configure { addRedaction("night", "hidden-by-rule") } }
            seek("hidden-by-rule")
            check(find("night") == null) { "Old privacy-policy values remain visible" }
            val count = changes()
            test.runOnMainSync { QaLens.configure { enabled = false } }
            seek("QaLens is disabled; values are hidden", contains = true)
            runBlocking { store.edit { it[theme] = "resumed-value" } }
            Thread.sleep(350)
            check(QaLens.state.value.dataSources[source]?.get("theme") != "resumed-value")
            check(changes() == count)
            test.runOnMainSync { QaLens.configure { enabled = true } }
            seek("resumed-value")
            check(changes() == count) { "Resubscription reported the initial read as a change" }
            QaLens.stopObservingDataStore(source)
            await("Stopped values remain registered") { source !in QaLens.state.value.dataSources }
            runBlocking { store.edit { it[theme] = "after-stop" } }
            Thread.sleep(350)
            check(source !in QaLens.state.value.dataSources && changes() == count)

            // A finite Flow still publishes its last value before its job completes.
            QaLens.observeDataStoreValues(source, flowOf("last-value")) { mapOf("theme" to it) }
            seek("last-value"); seek("Source ended · last values", contains = true)
            // Stopping the old observer must not delete a newer host snapshot with the same name.
            test.runOnMainSync { QaLens.registerDataSource(source) { mapOf("theme" to "host-replacement") } }
            QaLens.stopObservingDataStore(source)
            await("Stop deleted the host's replacement snapshot") { QaLens.state.value.dataSources[source]?.get("theme") == "host-replacement" }
            set("Search app data", "")
            QaLens.observeDataStoreValues(source, flowOf("bad")) { error("synthetic mapper failure") }
            seek("Source unavailable · last values", contains = true)
            check(QaLens.state.value.errors.any { it.message.contains("DataStore values '$source' unavailable") })

            test.runOnMainSync { QaLens.configure { addRedaction(".*private-tail$", "hidden-tail") } }
            click("Show storage files")
            click("Read preferences $plainPrefs")
            seek("prefs-day"); seek("[REDACTED]"); seek("hidden-tail")
            check(find("never-display-prefs-secret") == null)
            click("Read preferences $encryptedPrefs")
            seek("Encrypted preferences: connect decoded fields", contains = true)
            check(find("never-display-cipher-value") == null)
            check(find("Read preferences qalens_prefs") == null) { "SDK configuration is exposed as app preferences" }
            seek("File metadata only.", contains = true)
            seek(file.name + " ·", contains = true)
        } catch (failure: Throwable) {
            screenshot("qalens-data-ui-failure.png")
            throw failure
        } finally {
            test.runOnMainSync {
                QaLens.stopObservingDataStore(source)
                QaLens.configure { enabled = config.enabled; redactionRules = config.redactionRules }
                QaLensAppSal.setQueries(context, appSal.queries)
            }
            scope.cancel(); file.delete()
            databases.forEach { context.deleteDatabase(it) }
            for (name in listOf(plainPrefs, encryptedPrefs)) {
                context.getSharedPreferences(name, 0).edit().clear().commit()
                if (Build.VERSION.SDK_INT >= 24) context.deleteSharedPreferences(name)
            }
        }
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) test.uiAutomation.clearCache()
        return listOfNotNull(test.uiAutomation.rootInActiveWindow) + test.uiAutomation.windows.mapNotNull { it.root }
    }
    private fun find(label: String, contains: Boolean = false, editable: Boolean = false): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val text = node.text?.toString().orEmpty(); val desc = node.contentDescription?.toString().orEmpty()
            if ((!editable || node.isEditable) && (if (contains) text.contains(label) || desc.contains(label) else text == label || desc == label)) return node
            repeat(node.childCount) { node.getChild(it)?.let { child -> visit(child)?.let { return it } } }
            return null
        }
        return roots().firstNotNullOfOrNull { visit(it) }
    }
    private fun seek(label: String, contains: Boolean = false, editable: Boolean = false): AccessibilityNodeInfo {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val until = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < until) {
            find(label, contains, editable)?.let { node ->
                if (node.isVisibleToUser) return node
                if (node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)) {
                    Thread.sleep(150)
                    return@let
                }
            }
            fun scrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (node.isScrollable && node.className?.toString() != "android.widget.HorizontalScrollView") return node
                repeat(node.childCount) { node.getChild(it)?.let { scrollable(it)?.let { found -> return found } } }
                return null
            }
            val scroll = roots().firstNotNullOfOrNull { scrollable(it) }
            val table = if (label.startsWith("fixture-row-")) find("SQL result rows") else null
            table?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
            if ((table ?: scroll)?.performAction(direction) == false) direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            Thread.sleep(150)
        }
        error("App data UI control/value missing: $label")
    }
    private fun click(label: String) {
        var node = seek(label)
        while (!node.isClickable && node.parent != null) node = node.parent
        check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Could not click $label" }
        test.waitForIdleSync()
    }
    private fun set(label: String, value: String) {
        val field = seek(label, editable = true)
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Could not edit $label" }
        test.waitForIdleSync()
    }
    private fun checkButton(label: String) {
        val density = context.resources.displayMetrics.density
        val bounds = Rect()
        await("SQL action could not be brought fully into view: $label") {
            val node = seek(label)
            node.getBoundsInScreen(bounds)
            if (bounds.height() >= 48 * density) true else {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                Thread.sleep(200)
                false
            }
        }
        check(bounds.height() >= 48 * density && bounds.width() >= 100 * density) { "Cramped SQL action $label: $bounds" }
    }
    private fun screenshot(name: String) {
        Thread.sleep(200)
        test.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.cacheDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(100)
        check(condition()) { message }
    }
}
