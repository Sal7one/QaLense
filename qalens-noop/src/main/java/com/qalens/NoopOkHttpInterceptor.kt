package com.qalens

import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/** No-op interceptor: forwards the request untouched and records nothing. */
class QaLensOkHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request())
}

/** No-op Timber tree: discards everything. */
class QaLensTimberTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) = Unit
}
