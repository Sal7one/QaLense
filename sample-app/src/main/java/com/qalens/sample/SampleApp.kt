package com.qalens.sample

import android.app.Application
import com.qalens.QaLens
import com.qalens.QaLensTimberTree
import timber.log.Timber

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Mirror Timber logs into the QaLens timeline (debug builds plant the QaLens tree).
        Timber.plant(QaLensTimberTree())

        // Real on-disk data so QaLens's Database / App Data tooling has something to show:
        // a seeded SQLite DB (what Room would create) and a real SharedPreferences file.
        SampleDatabase.warmUp(this)
        getSharedPreferences("sample_settings", MODE_PRIVATE).edit()
            .putString("theme", "system")
            .putBoolean("onboarded", true)
            .putInt("launch_count", getSharedPreferences("sample_settings", MODE_PRIVATE).getInt("launch_count", 0) + 1)
            .apply()
        QaLens.configure {
            // Master run-condition: gate QaLens on whatever your team uses (flavor, env, remote
            // flag). When false, QaLensRoot/installer are fully inert — zero UI impact.
            enabled = true// BuildConfig.DEBUG
            appName = "QaLens Sample"
            appVersion = BuildConfig.VERSION_NAME
            buildVariant = BuildConfig.BUILD_TYPE
            environment = "staging"
            gitSha = "a91f22c"
            buildNumber = "8421"
            userType = "qa"
            slowNetworkThresholdMs = 1500L
            featureFlags = mapOf(
                "new_home" to true,
                "checkout_v2" to true,
                "wallet_refactor" to false
            )
        }
        // Feature flags can also come live from a provider (evaluated safely each analysis pass).
        QaLens.setFeatureFlagProvider {
            mapOf("kill_switch_transfers" to false)
        }

        // Deep-link smoke scenarios QA can launch from the panel (Tools tab) without Android Studio.
        QaLens.registerDeepLinkScenario("Open Accounts", "qalenssample://accounts",
            expectedRoute = "accounts", tags = listOf("smoke"))
        QaLens.registerDeepLinkScenario("Open Profile", "qalenssample://profile",
            expectedRoute = "profile", tags = listOf("smoke"))
        QaLens.registerDeepLinkScenario("Open Account #2", "qalenssample://account/2",
            expectedRoute = "account", tags = listOf("payments"))
        QaLens.registerDeepLinkScenario("Open Payment Failure", "qalenssample://payment-failure",
            expectedRoute = "payment-failure", tags = listOf("payments", "smoke"))

        // Optional live contracts for critical screens (shown in Screen Health).
        QaLens.contract("Transfer") {
            requiresTag("transfer.confirm")
            requiresTag("transfer.amount.field")
            requiresLabel("Confirm Transfer")
            requiresNoFailedNetwork()
        }
        QaLens.contract("Home") {
            requiresAnyTag("nav.bar", "nav.tab.home")
            requiresNoCriticalAccessibilityWarnings()
        }
        QaLens.contract("Payment Failure") {
            requiresTag("pay.retry")
            requiresLabel("Something went wrong")
            requiresNoFailedNetwork()
        }

        // App-provided data snapshots (real apps would read DataStore / query Room here).
        QaLens.registerDataSource("Preferences") { SamplePreferences.current }
        QaLens.registerDataSource("Database") {
            mapOf("accounts" to "4", "transactions" to "152", "pending_sync" to "0")
        }

        // Change events: a DataStore-style flow → timeline. (Room: QaLens.observeRoom(db, "table…").)
        QaLens.observeDataStore("Preferences", SamplePreferences.flow) { values ->
            values.entries.joinToString { "${it.key}=${it.value}" }
        }

        QaLens.install(this)
    }
}
