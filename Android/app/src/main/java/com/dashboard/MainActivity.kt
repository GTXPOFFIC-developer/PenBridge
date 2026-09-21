package com.dashboard

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dashboard.ui.DrawingScreen
import com.dashboard.ui.HomeScreen
import com.dashboard.ui.Screen
import com.dashboard.ui.SettingsScreen
import com.dashboard.ui.theme.DashboardTheme

/**
 * Single-activity Dashboard app. Screen switching keeps state alive via
 * AnimatedContent so the pen surface never loses its capture mid-stroke.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val app = application as DashboardApp
            val settings by app.settings.settings.collectAsState()
            DashboardTheme(accent = com.dashboard.ui.theme.accentPair(settings.accentIndex)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(app)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(app: DashboardApp) {
    var screen by remember { mutableStateOf(Screen.Home) }
    val view = LocalView.current

    // Immersive fullscreen only while drawing; restore bars elsewhere.
    LaunchedEffect(screen) {
        val activity = view.context as? android.app.Activity ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(activity.window, view)
        when (screen) {
            Screen.Draw -> {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            else -> {
                controller.show(WindowInsetsCompat.Type.systemBars())
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = {
            (slideInVertically { it / 3 } togetherWith slideOutVertically { -it / 3 })
        },
        label = "nav",
    ) { s ->
        when (s) {
            Screen.Home -> HomeScreen(
                app = app,
                onOpenDraw = { screen = Screen.Draw },
                onOpenSettings = { screen = Screen.Settings },
                onSignedIn = {
                    // re-run handshake so the new Google identity reaches the host
                    val m = app.settings.settings.value.mode
                    app.connection.stop()
                    app.connection.start(m, app.settings.settings.value.hostIp.takeIf { m == com.dashboard.core.ConnectMode.WIFI } ?: "")
                },
            )
            Screen.Draw -> DrawingScreen(app = app, onExit = { screen = Screen.Home })
            Screen.Settings -> SettingsScreen(app = app, onBack = { screen = Screen.Home })
        }
    }
}