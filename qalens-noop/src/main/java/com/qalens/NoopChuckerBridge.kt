package com.qalens

import android.content.Context

/** Release twin: never loads Chucker classes or launches an inspector. */
object QaLensChuckerBridge {
    fun isAvailable(context: Context): Boolean = false
    fun launch(context: Context): Boolean = false
}
