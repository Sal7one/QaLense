package com.qalens.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * QA profiles — one shared test phone, many testers. Each profile is a person: display name,
 * their Jira/backend username, and THEIR webhook identity (endpoint, auth header/bearer, params).
 * Activating a profile copies its values into the live webhook prefs, so every upload that
 * follows is authenticated and attributed as that person (`X-QaLens-User` header + `user=` param).
 *
 * Deliberately NOT part of `.appsal`: profiles carry personal bearer tokens; `.appsal` is the
 * shareable per-app config. Stored locally in the same prefs file.
 */
data class QaProfile(
    val name: String,
    val user: String = "",                       // Jira/backend username for attribution
    val webhookUrl: String = "",
    val headerName: String = "Authorization",
    val headerValue: String = "",
    val params: String = "",
    val includeMeta: Boolean = true
)

object QaLensProfiles {

    private const val KEY_PROFILES = "qa_profiles"
    private const val KEY_ACTIVE = "qa_active_profile"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("qalens_prefs", Context.MODE_PRIVATE)

    fun list(context: Context): List<QaProfile> = runCatching {
        val arr = JSONArray(prefs(context).getString(KEY_PROFILES, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { o ->
                QaProfile(
                    name = o.optString("name"),
                    user = o.optString("user"),
                    webhookUrl = o.optString("webhookUrl"),
                    headerName = o.optString("headerName", "Authorization"),
                    headerValue = o.optString("headerValue"),
                    params = o.optString("params"),
                    includeMeta = o.optBoolean("includeMeta", true)
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun store(context: Context, profiles: List<QaProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("name", p.name).put("user", p.user)
                    .put("webhookUrl", p.webhookUrl)
                    .put("headerName", p.headerName).put("headerValue", p.headerValue)
                    .put("params", p.params).put("includeMeta", p.includeMeta)
            )
        }
        prefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    /** Upsert by name. */
    fun save(context: Context, profile: QaProfile) {
        store(context, list(context).filterNot { it.name == profile.name } + profile)
    }

    fun delete(context: Context, name: String) {
        store(context, list(context).filterNot { it.name == name })
        if (activeName(context) == name) prefs(context).edit().remove(KEY_ACTIVE).apply()
    }

    fun activeName(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE, null)?.takeIf { it.isNotBlank() }

    /** Make [profile] the live identity: webhook prefs + user attribution all switch to them. */
    fun activate(context: Context, profile: QaProfile) {
        prefs(context).edit().putString(KEY_ACTIVE, profile.name).apply()
        QaLensPrefs.setWebhookUrl(context, profile.webhookUrl)
        QaLensPrefs.setWebhookHeaderName(context, profile.headerName)
        QaLensPrefs.setWebhookHeaderValue(context, profile.headerValue)
        QaLensPrefs.setWebhookParams(context, profile.params)
        QaLensPrefs.setWebhookIncludeMeta(context, profile.includeMeta)
        QaLensPrefs.setUserName(context, profile.user.ifBlank { profile.name })
    }

    /** Snapshot the current live webhook settings as a profile named [name] / [user]. */
    fun captureCurrent(context: Context, name: String, user: String): QaProfile = QaProfile(
        name = name,
        user = user,
        webhookUrl = QaLensPrefs.webhookUrl(context),
        headerName = QaLensPrefs.webhookHeaderName(context),
        headerValue = QaLensPrefs.webhookHeaderValue(context),
        params = QaLensPrefs.webhookParams(context),
        includeMeta = QaLensPrefs.webhookIncludeMeta(context)
    )

    /** Keep the stored active profile in sync after webhook fields are edited in the Control Room. */
    fun syncActiveFromPrefs(context: Context) {
        val name = activeName(context) ?: return
        val existing = list(context).firstOrNull { it.name == name } ?: return
        save(context, captureCurrent(context, name, existing.user))
    }
}
