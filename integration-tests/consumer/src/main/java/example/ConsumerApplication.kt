package example

import android.app.Application
import com.qalens.*
import okhttp3.OkHttpClient
import timber.log.Timber

/** Identical application source must compile against the active SDK and the release no-op. */
class ConsumerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        QaLens.configure { appName = "External consumer" }
        QaLens.install(this)
        OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor()).build()
        Timber.plant(QaLensTimberTree())
        QaLens.networkSink("Custom client").record(NetworkEvent(method = "GET", url = "https://example.test", status = 200))
        QaLens.reportCrash(QaLensCrash(type = CrashType.CRASH, thread = "test", throwable = null, stackTrace = "fixture"))
        QaLens.integrationReport()
        QaLensChuckerBridge.isAvailable(this)
    }
}
