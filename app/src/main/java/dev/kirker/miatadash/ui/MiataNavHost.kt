package dev.kirker.miatadash.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kirker.miatadash.feature.connect.ConnectScreen
import dev.kirker.miatadash.feature.dashboard.DashboardScreen
import dev.kirker.miatadash.feature.diagnostics.BrakeLogScreen
import dev.kirker.miatadash.feature.diagnostics.CanMonitorScreen
import dev.kirker.miatadash.feature.diagnostics.ConnectionStateScreen
import dev.kirker.miatadash.feature.diagnostics.DiagnosticsHomeScreen
import dev.kirker.miatadash.feature.diagnostics.LatencyScreen
import dev.kirker.miatadash.feature.diagnostics.PidExplorerScreen
import dev.kirker.miatadash.feature.diagnostics.RawConsoleScreen
import dev.kirker.miatadash.feature.diagnostics.TraceCaptureScreen
import dev.kirker.miatadash.feature.settings.SettingsScreen
import dev.kirker.miatadash.feature.smog.CatEfficiencyScreen
import dev.kirker.miatadash.feature.smog.DtcScreen
import dev.kirker.miatadash.feature.smog.ReadinessScreen
import dev.kirker.miatadash.feature.smog.SmogHomeScreen
import dev.kirker.miatadash.ui.components.ConnectionTopBar

object Routes {
    const val DASHBOARD = "dashboard"
    const val SMOG_HOME = "smog"
    const val SMOG_READINESS = "smog/readiness"
    const val SMOG_CAT = "smog/cat"
    const val SMOG_DTC = "smog/dtc"
    const val DIAG_HOME = "diag"
    const val DIAG_RAW = "diag/raw"
    const val DIAG_PID = "diag/pid"
    const val DIAG_CAN = "diag/can"
    const val DIAG_STATE = "diag/state"
    const val DIAG_LATENCY = "diag/latency"
    const val DIAG_TRACE = "diag/trace"
    const val DIAG_BRAKE_LOG = "diag/brake_log"
    const val SETTINGS = "settings"
    const val CONNECT = "connect"
}

private data class TopTab(val route: String, val label: String, val icon: @Composable () -> Unit)

private val topTabs = listOf(
    TopTab(Routes.DASHBOARD, "Dash") { Icon(Icons.Filled.Speed, null) },
    TopTab(Routes.SMOG_HOME, "Smog") { Icon(Icons.Filled.LocalGasStation, null) },
    TopTab(Routes.DIAG_HOME, "Diag") { Icon(Icons.Filled.BugReport, null) },
    TopTab(Routes.SETTINGS, "Settings") { Icon(Icons.Filled.Settings, null) },
)

@Composable
fun MiataNavHost() {
    // Keep the screen on for the entire time the app is in the foreground.
    // DisposableEffect releases the flag when MiataNavHost leaves composition
    // (app backgrounded or closed), so the screen resumes normal timeout behaviour.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = { ConnectionTopBar() },
        bottomBar = {
            NavigationBar {
                topTabs.forEach { tab ->
                    val selected = currentRoute?.startsWith(tab.route) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = tab.icon,
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            NavHost(navController = nav, startDestination = Routes.DASHBOARD) {
                composable(Routes.DASHBOARD)        { DashboardScreen(onConnect = { nav.navigate(Routes.CONNECT) }) }
                composable(Routes.CONNECT)          { ConnectScreen(onDone = { nav.popBackStack() }) }

                composable(Routes.SMOG_HOME)        { SmogHomeScreen(nav) }
                composable(Routes.SMOG_READINESS)   { ReadinessScreen() }
                composable(Routes.SMOG_CAT)         { CatEfficiencyScreen() }
                composable(Routes.SMOG_DTC)         { DtcScreen() }

                composable(Routes.DIAG_HOME)        { DiagnosticsHomeScreen(nav) }
                composable(Routes.DIAG_RAW)         { RawConsoleScreen() }
                composable(Routes.DIAG_PID)         { PidExplorerScreen() }
                composable(Routes.DIAG_CAN)         { CanMonitorScreen() }
                composable(Routes.DIAG_STATE)       { ConnectionStateScreen() }
                composable(Routes.DIAG_LATENCY)     { LatencyScreen() }
                composable(Routes.DIAG_TRACE)       { TraceCaptureScreen() }
                composable(Routes.DIAG_BRAKE_LOG)   { BrakeLogScreen() }

                composable(Routes.SETTINGS)         { SettingsScreen() }
            }
        }
    }
}
