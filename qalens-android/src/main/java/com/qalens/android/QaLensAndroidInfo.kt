package com.qalens.android

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.View
import com.qalens.DeviceSnapshot
import com.qalens.QaLensConfig
import com.qalens.ScreenSnapshot

object QaLensAndroidInfo {
    fun deviceSnapshot(activity: Activity, config: QaLensConfig): DeviceSnapshot {
        val packageInfo = runCatching {
            activity.packageManager.getPackageInfo(activity.packageName, 0)
        }.getOrNull()
        val density = activity.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val configuration = activity.resources.configuration

        return DeviceSnapshot(
            appName = config.appName.ifBlank { activity.applicationInfo.loadLabel(activity.packageManager).toString() },
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
            isRtl = activity.window.decorView.layoutDirection == View.LAYOUT_DIRECTION_RTL,
            userType = config.userType,
            featureFlags = config.featureFlags
        )
    }

    fun screenSnapshot(activity: Activity, current: ScreenSnapshot): ScreenSnapshot =
        current.copy(activityName = activity::class.java.name)
}
