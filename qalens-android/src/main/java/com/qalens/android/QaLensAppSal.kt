package com.qalens.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The `.appsal` format (`qalens-appsal/1`) — one JSON document holding everything QA configured
 * for ONE app package: panel style, overlay prefs, webhook, saved SQL queries, macros, and the
 * data files to watch. Import/export lives in the Control Room; the web player renders it in the
 * App Config viewer; `web/sample.appsal` is the demo.
 *
 * Secrets: the webhook auth header VALUE is exported as "•••" unless the QA engineer explicitly
 * chooses "include secrets" — configs are meant to be shared around a team.
 */
data class AppSalQuery(val name: String, val db: String, val sql: String)
data class AppSalMacro(val name: String, val steps: List<String>)

data class AppSalConfig(
    val packageName: String,
    val name: String = "",
    val exportedAt: Long = 0L,
    // ui
    val panelMode: String = "full",          // "minimal" | "full"
    val overlayAlpha: Float = 1f,
    val dockBottom: Boolean = false,
    // webhook
    val webhookUrl: String = "",
    val webhookHeaderName: String = "Authorization",
    val webhookHeaderValue: String = "",
    val webhookParams: String = "",
    val webhookIncludeMeta: Boolean = true,
    // tooling
    val queries: List<AppSalQuery> = emptyList(),
    val macros: List<AppSalMacro> = emptyList(),
    val watchPrefs: List<String> = emptyList()
)

object QaLensAppSal {

    const val FORMAT = "qalens-appsal/1"
    private const val KEY_QUERIES = "appsal_queries"
    private const val KEY_MACROS = "appsal_macros"
    private const val KEY_WATCH_PREFS = "appsal_watch_prefs"
    private const val KEY_PANEL_MODE = "panel_mode"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("qalens_prefs", Context.MODE_PRIVATE)

    // ── Panel mode (minimal vs full) ─────────────────────────────────────────
    fun panelMode(context: Context): String = prefs(context).getString(KEY_PANEL_MODE, "full") ?: "full"
    fun setPanelMode(context: Context, mode: String) =
        prefs(context).edit().putString(KEY_PANEL_MODE, if (mode == "minimal") "minimal" else "full").apply()

    // ── Saved queries / macros / watch list (persisted as JSON arrays) ──────
    fun queries(context: Context): List<AppSalQuery> =
        runCatching {
            val arr = JSONArray(prefs(context).getString(KEY_QUERIES, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    AppSalQuery(it.optString("name"), it.optString("db"), it.optString("sql"))
                }
            }
        }.getOrDefault(emptyList())

    fun setQueries(context: Context, list: List<AppSalQuery>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("db", it.db).put("sql", it.sql))
        }
        prefs(context).edit().putString(KEY_QUERIES, arr.toString()).apply()
    }

    fun macros(context: Context): List<AppSalMacro> =
        runCatching {
            val arr = JSONArray(prefs(context).getString(KEY_MACROS, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    val steps = o.optJSONArray("steps") ?: JSONArray()
                    AppSalMacro(o.optString("name"), (0 until steps.length()).map { s -> steps.optString(s) })
                }
            }
        }.getOrDefault(emptyList())

    fun setMacros(context: Context, list: List<AppSalMacro>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("name", m.name).put("steps", JSONArray(m.steps)))
        }
        prefs(context).edit().putString(KEY_MACROS, arr.toString()).apply()
    }

    // ── Macro usage (device-local, NOT exported — recency feeds the minimal panel) ──
    private const val KEY_MACRO_USAGE = "appsal_macro_usage"

    fun recordMacroUse(context: Context, name: String) {
        val o = runCatching { JSONObject(prefs(context).getString(KEY_MACRO_USAGE, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        o.put(name, System.currentTimeMillis())
        prefs(context).edit().putString(KEY_MACRO_USAGE, o.toString()).apply()
    }

    /** Macros ordered most-recently-used first (never-used ones keep their stored order, last). */
    fun recentMacros(context: Context, limit: Int = 5): List<AppSalMacro> {
        val usage = runCatching { JSONObject(prefs(context).getString(KEY_MACRO_USAGE, "{}") ?: "{}") }
            .getOrDefault(JSONObject())
        return macros(context)
            .sortedByDescending { usage.optLong(it.name, 0L) }
            .take(limit)
    }

    fun watchPrefs(context: Context): List<String> =
        (prefs(context).getString(KEY_WATCH_PREFS, "") ?: "").split(',').filter { it.isNotBlank() }

    fun setWatchPrefs(context: Context, list: List<String>) =
        prefs(context).edit().putString(KEY_WATCH_PREFS, list.joinToString(",")).apply()

    // ── Export / import ──────────────────────────────────────────────────────

    /** Snapshot the device's current config for this app as an [AppSalConfig]. */
    fun current(context: Context): AppSalConfig = AppSalConfig(
        packageName = context.packageName,
        name = "${context.packageName} QA config",
        exportedAt = System.currentTimeMillis(),
        panelMode = panelMode(context),
        overlayAlpha = QaLensPrefs.overlayAlpha(context),
        dockBottom = QaLensPrefs.dockBottom(context),
        webhookUrl = QaLensPrefs.webhookUrl(context),
        webhookHeaderName = QaLensPrefs.webhookHeaderName(context),
        webhookHeaderValue = QaLensPrefs.webhookHeaderValue(context),
        webhookParams = QaLensPrefs.webhookParams(context),
        webhookIncludeMeta = QaLensPrefs.webhookIncludeMeta(context),
        queries = queries(context),
        macros = macros(context),
        watchPrefs = watchPrefs(context)
    )

    fun encode(c: AppSalConfig, includeSecrets: Boolean): String {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("package", c.packageName)
        o.put("name", c.name)
        o.put("exportedAt", c.exportedAt)
        o.put("ui", JSONObject()
            .put("panelMode", c.panelMode)
            .put("overlayAlpha", c.overlayAlpha.toDouble())
            .put("dockBottom", c.dockBottom))
        o.put("webhook", JSONObject()
            .put("url", c.webhookUrl)
            .put("headerName", c.webhookHeaderName)
            .put("headerValue", if (includeSecrets) c.webhookHeaderValue
                                else if (c.webhookHeaderValue.isBlank()) "" else "•••")
            .put("params", c.webhookParams)
            .put("includeMeta", c.webhookIncludeMeta))
        o.put("queries", JSONArray().also { arr ->
            c.queries.forEach { arr.put(JSONObject().put("name", it.name).put("db", it.db).put("sql", it.sql)) }
        })
        o.put("macros", JSONArray().also { arr ->
            c.macros.forEach { arr.put(JSONObject().put("name", it.name).put("steps", JSONArray(it.steps))) }
        })
        o.put("data", JSONObject().put("watchPrefs", JSONArray(c.watchPrefs)))
        return o.toString(2)
    }

    fun decode(text: String): AppSalConfig? = runCatching {
        val o = JSONObject(text)
        if (o.optString("format") != FORMAT) return null
        val ui = o.optJSONObject("ui") ?: JSONObject()
        val wh = o.optJSONObject("webhook") ?: JSONObject()
        val data = o.optJSONObject("data") ?: JSONObject()
        val queries = (o.optJSONArray("queries") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { AppSalQuery(it.optString("name"), it.optString("db"), it.optString("sql")) }
            }
        }
        val macros = (o.optJSONArray("macros") ?: JSONArray()).let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { m ->
                    val steps = m.optJSONArray("steps") ?: JSONArray()
                    AppSalMacro(m.optString("name"), (0 until steps.length()).map { s -> steps.optString(s) })
                }
            }
        }
        val watch = (data.optJSONArray("watchPrefs") ?: JSONArray()).let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        }
        AppSalConfig(
            packageName = o.optString("package"),
            name = o.optString("name"),
            exportedAt = o.optLong("exportedAt"),
            panelMode = ui.optString("panelMode", "full"),
            overlayAlpha = ui.optDouble("overlayAlpha", 1.0).toFloat(),
            dockBottom = ui.optBoolean("dockBottom", false),
            webhookUrl = wh.optString("url"),
            webhookHeaderName = wh.optString("headerName", "Authorization"),
            webhookHeaderValue = wh.optString("headerValue").takeIf { it != "•••" } ?: "",
            webhookParams = wh.optString("params"),
            webhookIncludeMeta = wh.optBoolean("includeMeta", true),
            queries = queries,
            macros = macros,
            watchPrefs = watch
        )
    }.getOrNull()

    /**
     * Apply an imported config to this device. Skips a masked/empty webhook secret so importing a
     * shared config never wipes a locally configured token. Returns a short human summary.
     */
    fun apply(context: Context, c: AppSalConfig): String {
        setPanelMode(context, c.panelMode)
        QaLensPrefs.setOverlayAlpha(context, c.overlayAlpha)
        QaLensPrefs.setDockBottom(context, c.dockBottom)
        if (c.webhookUrl.isNotBlank()) QaLensPrefs.setWebhookUrl(context, c.webhookUrl)
        QaLensPrefs.setWebhookHeaderName(context, c.webhookHeaderName)
        if (c.webhookHeaderValue.isNotBlank()) QaLensPrefs.setWebhookHeaderValue(context, c.webhookHeaderValue)
        QaLensPrefs.setWebhookParams(context, c.webhookParams)
        QaLensPrefs.setWebhookIncludeMeta(context, c.webhookIncludeMeta)
        setQueries(context, c.queries)
        setMacros(context, c.macros)
        setWatchPrefs(context, c.watchPrefs)
        return "panel=${c.panelMode} · ${c.queries.size} queries · ${c.macros.size} macros" +
            (if (c.webhookUrl.isNotBlank()) " · webhook set" else "")
    }
}
