package example

import android.app.Application
import android.view.View
import androidx.compose.ui.Modifier
import androidx.room.RoomDatabase
import com.qalens.*
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import timber.log.Timber

/** Identical application source must compile against the active SDK and the release no-op. */
class ConsumerApplication : Application() {
    /** Compile the same optional Compose integration calls against debug and release artifacts. */
    @Suppress("unused")
    private fun inspectionApiParity(view: View): Modifier {
        QaLens.startLocalBridge("synthetic-compile-only-0123456789")
        QaLens.stopLocalBridge()
        QaLens.saveRecentClip(seconds = 20, label = "Compile-only clip")
        QaLens.localBridgeStatus.value
        QaLens.invalidateInspection()
        QaLens.registerComposeRoot(view)
        QaLens.unregisterComposeRoot(view)
        return Modifier.qaInspectionRoot()
    }

    @Suppress("unused")
    private fun dataHookParity(db: RoomDatabase, values: Flow<Int>) {
        QaLens.observeRoom(db, "entries")
        QaLens.stopObservingRoom(db, "entries")
        QaLens.observeDataStore("Prefs", values) { "updated" }
        QaLens.observeDataStoreValues("Settings", values, redactKeys = listOf("account")) { value ->
            mapOf("count" to value.toString())
        }
        QaLens.stopObservingDataStore("Settings")
        QaLens.stopObservingDataStore("Prefs")
    }

    override fun onCreate() {
        super.onCreate()
        QaLens.configure { appName = "External consumer"; allowUnmaskedVideo = false; saveScreenshotsToGallery = false; recordingMaxDurationMinutes = 60; recordingClipPresetsSeconds = listOf(10, 20, 60) }
        QaLens.install(this)
        OkHttpClient.Builder().addInterceptor(QaLensOkHttpInterceptor()).build()
        Timber.plant(QaLensTimberTree())
        QaLens.networkSink("Custom client").record(NetworkEvent(method = "GET", url = "https://example.test", status = 200))
        QaLens.reportCrash(QaLensCrash(type = CrashType.CRASH, thread = "test", throwable = null, stackTrace = "fixture"))
        QaLens.coroutineExceptionHandler()
        QaLensCoroutineExceptionHandler.capture()
        QaLens.integrationReport()
        QaLensChuckerBridge.isAvailable(this)
    }
}
