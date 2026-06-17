package com.qalens.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import com.qalens.QaLens

/**
 * Observes [navController] back stack changes and automatically calls [QaLens.setScreen] +
 * [QaLens.breadcrumb] on every destination change. Drop this anywhere inside a [QaLens] root.
 *
 * @param routeNameMapper maps raw route strings (e.g. "details/{id}") to a human-readable screen
 *   name. Defaults to stripping path/query params so "details/42?tab=0" → "details".
 */
@Composable
fun QaLensNavigationObserver(
    navController: NavHostController,
    routeNameMapper: (String?) -> String? = { route ->
        route?.substringBefore("/")?.substringBefore("?")
    }
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route

    LaunchedEffect(route) {
        if (route != null) {
            val name = routeNameMapper(route) ?: route
            QaLens.setScreen(name = name, route = route)
            QaLens.breadcrumb("Navigation → $route")
        }
    }
}

/**
 * Drop-in replacement for [NavHost] that automatically tracks route changes via
 * [QaLensNavigationObserver]. Keeps the rest of your nav graph unchanged.
 */
@Composable
fun QaLensNavHost(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    routeNameMapper: (String?) -> String? = { route ->
        route?.substringBefore("/")?.substringBefore("?")
    },
    builder: NavGraphBuilder.() -> Unit
) {
    QaLensNavigationObserver(
        navController = navController,
        routeNameMapper = routeNameMapper
    )

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        builder = builder
    )
}

/**
 * Optional wrapper around [NavHostController] that logs each [navigate] and [popBackStack] call
 * as a QaLens breadcrumb. Useful when you want to see the intent-to-navigate, not just the
 * resulting destination (which [QaLensNavigationObserver] already captures).
 *
 * Use only if you want layer-3 visibility; [QaLensNavHost] is sufficient for most apps.
 */
class QaLensNavigator(val navController: NavHostController) {

    fun navigate(route: String) {
        QaLens.breadcrumb("Navigate intent → $route")
        navController.navigate(route)
    }

    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) {
        QaLens.breadcrumb("Navigate intent → $route")
        navController.navigate(route, builder)
    }

    fun popBackStack(): Boolean {
        val popped = navController.popBackStack()
        if (popped) QaLens.breadcrumb("Back stack popped")
        return popped
    }

    fun popBackStack(route: String, inclusive: Boolean): Boolean {
        val popped = navController.popBackStack(route, inclusive)
        if (popped) QaLens.breadcrumb("Popped back to: $route (inclusive=$inclusive)")
        return popped
    }
}
