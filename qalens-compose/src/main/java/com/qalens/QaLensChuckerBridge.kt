package com.qalens

import android.content.Context
import android.content.Intent

/**
 * Launches [Chucker](https://github.com/ChuckerTeam/chucker)'s network inspector activity if Chucker
 * is on the debug classpath. QaLens and Chucker are complementary:
 * - **Chucker** = full request/response body inspection on-device (its own UI).
 * - **QaLens** = redacted metadata + evidence bundle + `.sal` recording + AI digest.
 *
 * Both can intercept the same `OkHttpClient` — Chucker first (it needs the raw body), QaLens second
 * (it only reads metadata, never touches the body). Recommended setup:
 * ```kotlin
 * OkHttpClient.Builder()
 *     .addInterceptor(ChuckerInterceptor.Builder(context).build())  // first — full bodies
 *     .addInterceptor(QaLensOkHttpInterceptor())                    // second — metadata only
 *     .build()
 * ```
 *
 * This bridge uses reflection so the Chucker dependency stays optional — if Chucker is absent,
 * [isAvailable] returns false and the "Open Chucker" button is hidden.
 */
object QaLensChuckerBridge {

    /** True if Chucker's launcher activity is on the classpath. */
    fun isAvailable(context: Context): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage("com.github.chuckerte.chucker")
            ?: run {
                Class.forName("com.chuckerteam.chucker.api.Chucker")
                true
            }
        true
    }.getOrDefault(false)

    /**
     * Launch Chucker's main activity. Returns true if launched, false if Chucker isn't available.
     * Uses reflection so QaLens doesn't need Chucker as a hard dependency.
     */
    fun launch(context: Context): Boolean = runCatching {
        // Chucker 4.x: com.chuckerteam.chucker.api.Chucker.launch(context)
        val clazz = Class.forName("com.chuckerteam.chucker.api.Chucker")
        val method = clazz.getMethod("launch", Context::class.java)
        method.invoke(null, context)
        true
    }.onFailure {
        QaLens.pushError(ErrorKind.OTHER, "Failed to launch Chucker: ${it.message}")
    }.getOrDefault(false)
}
