package com.qalens.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny persisted settings store for QaLens (SharedPreferences-backed). Holds overlay/UX preferences
 * so QA setups survive process death: overlay opacity, dock side, whether the overlay is injected at
 * all, and the default recording mode. Debug/QA only — never holds app or user data.
 */
object QaLensPrefs {

    private const val FILE = "qalens_prefs"

    private const val KEY_ALPHA = "overlay_alpha"
    private const val KEY_DOCK_BOTTOM = "dock_bottom"
    private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
    private const val KEY_DEFAULT_VIDEO = "default_video_recording"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun overlayAlpha(context: Context): Float = prefs(context).getFloat(KEY_ALPHA, 1f)
    fun setOverlayAlpha(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_ALPHA, value).apply()

    fun dockBottom(context: Context): Boolean = prefs(context).getBoolean(KEY_DOCK_BOTTOM, false)
    fun setDockBottom(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DOCK_BOTTOM, value).apply()

    fun overlayEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_OVERLAY_ENABLED, true)
    fun setOverlayEnabled(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, value).apply()

    fun defaultVideoRecording(context: Context): Boolean = prefs(context).getBoolean(KEY_DEFAULT_VIDEO, false)
    fun setDefaultVideoRecording(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_DEFAULT_VIDEO, value).apply()

    // ── Bubble position (drag offset from its top-end anchor, px) ───────────
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"

    fun bubbleX(context: Context): Float = prefs(context).getFloat(KEY_BUBBLE_X, 0f)
    fun bubbleY(context: Context): Float = prefs(context).getFloat(KEY_BUBBLE_Y, 0f)
    fun setBubblePos(context: Context, x: Float, y: Float) =
        prefs(context).edit().putFloat(KEY_BUBBLE_X, x).putFloat(KEY_BUBBLE_Y, y).apply()

    // ── Webhook (Control Room → AI-analysis backend) ───────────────────────
    private const val KEY_WEBHOOK_URL = "webhook_url"
    private const val KEY_WEBHOOK_HEADER_NAME = "webhook_header_name"
    private const val KEY_WEBHOOK_HEADER_VALUE = "webhook_header_value"
    private const val KEY_WEBHOOK_PARAMS = "webhook_params"
    private const val KEY_WEBHOOK_META = "webhook_meta"

    fun webhookUrl(context: Context): String = prefs(context).getString(KEY_WEBHOOK_URL, "") ?: ""
    fun setWebhookUrl(context: Context, value: String) =
        prefs(context).edit().putString(KEY_WEBHOOK_URL, value.trim()).apply()

    fun webhookHeaderName(context: Context): String =
        prefs(context).getString(KEY_WEBHOOK_HEADER_NAME, "Authorization") ?: "Authorization"
    fun setWebhookHeaderName(context: Context, value: String) =
        prefs(context).edit().putString(KEY_WEBHOOK_HEADER_NAME, value.trim()).apply()

    fun webhookHeaderValue(context: Context): String = prefs(context).getString(KEY_WEBHOOK_HEADER_VALUE, "") ?: ""
    fun setWebhookHeaderValue(context: Context, value: String) =
        prefs(context).edit().putString(KEY_WEBHOOK_HEADER_VALUE, value.trim()).apply()

    /** Extra query params as a raw "k=v&k2=v2" string, appended to the webhook URL. */
    fun webhookParams(context: Context): String = prefs(context).getString(KEY_WEBHOOK_PARAMS, "") ?: ""
    fun setWebhookParams(context: Context, value: String) =
        prefs(context).edit().putString(KEY_WEBHOOK_PARAMS, value.trim()).apply()

    fun webhookIncludeMeta(context: Context): Boolean = prefs(context).getBoolean(KEY_WEBHOOK_META, true)
    fun setWebhookIncludeMeta(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(KEY_WEBHOOK_META, value).apply()

    // ── Active tester (set by QaLensProfiles.activate) ───────────────────────
    private const val KEY_USER_NAME = "qa_user_name"

    fun userName(context: Context): String = prefs(context).getString(KEY_USER_NAME, "") ?: ""
    fun setUserName(context: Context, value: String) =
        prefs(context).edit().putString(KEY_USER_NAME, value.trim()).apply()
}
