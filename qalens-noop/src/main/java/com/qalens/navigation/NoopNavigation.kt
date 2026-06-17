package com.qalens.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost

/**
 * Release / no-op navigation wrappers. Same API as qalens-navigation-compose, but they do not
 * observe or report anything. [QaLensNavHost] still renders the real [NavHost] so the app's
 * navigation is completely unaffected.
 */
@Composable
fun QaLensNavigationObserver(
    navController: NavHostController,
    routeNameMapper: (String?) -> String? = { it?.substringBefore("/")?.substringBefore("?") }
) {
    // no-op
}

@Composable
fun QaLensNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    routeNameMapper: (String?) -> String? = { it?.substringBefore("/")?.substringBefore("?") },
    builder: NavGraphBuilder.() -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        builder = builder
    )
}

class QaLensNavigator(val navController: NavHostController) {
    fun navigate(route: String) = navController.navigate(route)
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) = navController.navigate(route, builder)
    fun popBackStack(): Boolean = navController.popBackStack()
    fun popBackStack(route: String, inclusive: Boolean): Boolean = navController.popBackStack(route, inclusive)
}
