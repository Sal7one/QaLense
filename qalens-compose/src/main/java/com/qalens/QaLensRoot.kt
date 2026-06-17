package com.qalens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun QaLensRoot(
    modifier: Modifier = Modifier,
    screenName: String? = null,
    route: String? = null,
    testTagsAsResourceId: Boolean = true,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    // Run-condition gate: when the app's condition (this parameter) or the configured master
    // switch (`QaLens.configure { enabled = ... }`) says no, QaLensRoot must cost NOTHING —
    // no wrapper Box, no semantics flag, no effects, no overlay attach. Just the app's content.
    val config by QaLens.config.collectAsStateWithLifecycle()
    if (!enabled || !config.enabled) {
        content()
    } else {
        val view = LocalView.current

        LaunchedEffect(screenName, route) {
            if (screenName != null || route != null) {
                QaLens.setScreen(screenName ?: route.orEmpty(), route)
            }
        }

        // The overlay has ONE owner: the decor-attached ComposeView created by the activity installer.
        // QaLensRoot only ensures it's attached (idempotent — guarded by view tag), so the overlay also
        // works when auto-install is disabled. It deliberately does NOT render its own QaLensOverlay():
        // doing so alongside the installer's overlay would double every bubble, panel, and watch HUD.
        LaunchedEffect(view) {
            view.context.findActivity()?.let { QaLens.attachOverlay(it) }
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .then(
                    if (testTagsAsResourceId) Modifier.semantics {
                        this.testTagsAsResourceId = true
                    }
                    else Modifier
                )
        ) {
            content()
        }
    }

}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
