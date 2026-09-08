package com.qalens.sample

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.qalens.QaLensOkHttpInterceptor
import okhttp3.OkHttpClient

/** Copyable coexistence example. Both public classes have release no-op twins. */
object SampleOssTools {
    fun httpClient(context: Context): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ChuckerInterceptor.Builder(context.applicationContext)
            .collector(ChuckerCollector(context.applicationContext, showNotification = false))
            .redactHeaders("Authorization", "Cookie", "Set-Cookie")
            .build())
        .addInterceptor(QaLensOkHttpInterceptor())
        .build()
}
