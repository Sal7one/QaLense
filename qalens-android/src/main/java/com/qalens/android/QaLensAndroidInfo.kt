package com.qalens.android

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.View
import com.qalens.DeviceSnapshot
import com.qalens.QaLensConfig
import com.qalens.ScreenSnapshot

object QaLensAndroidInfo {
    /** Activity-independent metadata for uploads started from the standalone Control Room. */
    fun deviceSnapshot(context: Context, config: QaLensConfig): DeviceSnapshot {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val configuration = context.resources.configuration

        return DeviceSnapshot(
            appName = config.appName.ifBlank { context.applicationInfo.loadLabel(context.packageManager).toString() },
            appVersion = config.appVersion.ifBlank { packageInfo?.versionName.orEmpty() },
            versionCode = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            } ?: 0L,
            buildVariant = config.buildVariant,
            gitSha = config.gitSha,
            buildNumber = config.buildNumber,
            environment = config.environment,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkVersion = Build.VERSION.SDK_INT,
            screenWidthDp = configuration.screenWidthDp,
            screenHeightDp = configuration.screenHeightDp,
            density = density,
            fontScale = configuration.fontScale,
            isRtl = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            userType = config.userType,
            featureFlags = config.featureFlags
        )
    }

    fun deviceSnapshot(activity: Activity, config: QaLensConfig): DeviceSnapshot =
        deviceSnapshot(activity as Context, config).copy(
            isRtl = activity.window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL
        )

    fun screenSnapshot(activity: Activity, current: ScreenSnapshot): ScreenSnapshot =
        current.copy(activityName = activity::class.java.name)
}
