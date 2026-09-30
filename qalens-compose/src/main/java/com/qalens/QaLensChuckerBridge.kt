package com.qalens

import android.content.Context
import android.content.Intent

/** Optional launcher using Chucker's public API; never reads its internal database or bodies.
 * Attach ChuckerInterceptor and QaLensOkHttpInterceptor to the same client for complementary views.
 */
object QaLensChuckerBridge {
    private fun api(): Class<*> = Class.forName("com.chuckerteam.chucker.api.Chucker")

    /** The no-op artifact has the same class name; check isOp rather than class presence alone. */
    fun isAvailable(context: Context): Boolean = runCatching {
        val clazz = api()
        val instance = clazz.getField("INSTANCE").get(null)
        clazz.getMethod("isOp").invoke(instance) == true &&
            clazz.getMethod("getLaunchIntent", Context::class.java) != null
    }.getOrDefault(false)

    fun launch(context: Context): Boolean = runCatching {
        if (!isAvailable(context)) return false
        val intent = api().getMethod("getLaunchIntent", Context::class.java).invoke(null, context) as Intent
        context.startActivity(intent)
        true
    }.onFailure {
        QaLens.pushError(ErrorKind.OTHER, "Failed to open Chucker: ${it.javaClass.simpleName}")
    }.getOrDefault(false)
}
