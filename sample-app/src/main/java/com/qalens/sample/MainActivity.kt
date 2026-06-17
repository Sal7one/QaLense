package com.qalens.sample

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.qalens.QaLensRoot
import com.qalens.navigation.QaLensNavHost
import com.qalens.qaTag

private val topLevel = setOf("home", "accounts", "settings")

private fun routeName(route: String?) = when {
    route == null                      -> "Unknown"
    route == "home"                    -> "Home"
    route == "accounts"                -> "Accounts"
    route == "settings"                -> "Settings"
    route == "profile"                 -> "Profile"
    route == "payment-failure"         -> "Payment Failure"
    route.startsWith("account/")       -> "Account Detail"
    route.startsWith("transfer/")      -> "Transfer"
    else                               -> route
}

class MainActivity : ComponentActivity() {
    // Holds deep-link intents delivered while the activity is already running (singleTop).
    private val pendingIntent = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent.value = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(false) }
            SampleTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()
                // Route deep links that arrive after launch (QaLens scenario runner uses these).
                val intent by pendingIntent
                LaunchedEffect(intent) {
                    intent?.let { navController.handleDeepLink(it); pendingIntent.value = null }
                }
                QaLensRoot(testTagsAsResourceId = true) {
                    AppNavHost(navController, darkTheme) {
                        darkTheme = !darkTheme
                        SamplePreferences.setTheme(darkTheme) // emits a DataStore-style change event
                    }
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(
    navController: NavHostController,
    darkTheme: Boolean,
    onToggleDark: () -> Unit
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = currentRoute in topLevel

    Scaffold(
        bottomBar = {
            if (showBar) BottomBar(navController, currentRoute)
        }
    ) { pad ->
        QaLensNavHost(
            navController    = navController,
            startDestination = "home",
            modifier         = Modifier.padding(pad),
            routeNameMapper  = ::routeName
        ) {
            composable("home") {
                HomeScreen(
                    onAccountClick  = { navController.navigate("account/$it") },
                    onTransferClick = { navController.navigate("transfer/$it") }
                )
            }
            composable(
                "accounts",
                deepLinks = listOf(navDeepLink { uriPattern = "qalenssample://accounts" })
            ) {
                AccountsScreen(onAccountClick = { navController.navigate("account/$it") })
            }
            composable("settings") {
                SettingsScreen(
                    darkTheme     = darkTheme,
                    onToggleDark  = onToggleDark,
                    onProfileClick = { navController.navigate("profile") }
                )
            }
            composable(
                "account/{id}",
                deepLinks = listOf(navDeepLink { uriPattern = "qalenssample://account/{id}" })
            ) { back ->
                val id = back.arguments?.getString("id")?.toIntOrNull() ?: 1
                AccountDetailScreen(
                    accountId   = id,
                    onTransfer  = { navController.navigate("transfer/$it") },
                    onBack      = { navController.popBackStack() }
                )
            }
            composable("transfer/{fromId}") { back ->
                val fromId = back.arguments?.getString("fromId")?.toIntOrNull() ?: 1
                TransferScreen(
                    fromAccountId = fromId,
                    onBack        = { navController.popBackStack() }
                )
            }
            composable(
                "profile",
                deepLinks = listOf(navDeepLink { uriPattern = "qalenssample://profile" })
            ) {
                ProfileScreen(onBack = { navController.popBackStack() })
            }
            composable(
                "payment-failure",
                deepLinks = listOf(navDeepLink { uriPattern = "qalenssample://payment-failure" })
            ) {
                PaymentFailureScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

private enum class NavDest(val route: String, val label: String, val icon: String) {
    HOME("home", "Home", "⌂"),
    ACCOUNTS("accounts", "Cards", "▣"),
    SETTINGS("settings", "More", "≡"),
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String?) {
    NavigationBar(modifier = Modifier.qaTag("nav.bar")) {
        NavDest.entries.forEach { dest ->
            NavigationBarItem(
                selected = currentRoute == dest.route,
                onClick = {
                    navController.navigate(dest.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState    = true
                    }
                },
                label = { Text(dest.label) },
                icon  = { Text(dest.icon) },
                modifier = Modifier.qaTag("nav.tab.${dest.route}")
            )
        }
    }
}
