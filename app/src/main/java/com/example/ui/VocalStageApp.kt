package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.StageViewModel
import com.example.model.AudioSourceMode
import com.example.ui.screens.KeyDetectorScreen
import com.example.ui.screens.MonitorDashboardScreen
import com.example.ui.screens.PresetsAndSetupScreen
import com.example.ui.screens.WebSocketControlScreen
import com.example.ui.theme.LedBlue
import com.example.ui.theme.PedalCrimson
import com.example.ui.theme.StageObsidian
import com.example.ui.theme.StageRackSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private data class NavDestinationItem(
    val route: String,
    val labelResId: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private const val ROUTE_KEY_DETECT = "key_detect"
private const val ROUTE_MONITOR = "monitor"
private const val ROUTE_WS = "ws_control"
private const val ROUTE_PRESETS = "presets_setup"

@Composable
fun VocalStageApp(viewModel: StageViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val pedalState by viewModel.pedalState.collectAsStateWithLifecycle()
    val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
    val dispatchStatus by viewModel.dispatchStatus.collectAsStateWithLifecycle()
    val wsStatus by viewModel.wsStatus.collectAsStateWithLifecycle()
    val wsLogs by viewModel.wsLogs.collectAsStateWithLifecycle()
    val wsRules by viewModel.wsRules.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val inputDevices by viewModel.inputDevices.collectAsStateWithLifecycle()
    val outputDevices by viewModel.outputDevices.collectAsStateWithLifecycle()
    val usbDiagnostic by viewModel.usbDiagnostic.collectAsStateWithLifecycle()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* optional foreground notification permission */ }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            viewModel.startAudioEngine()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    "กรุณาอนุญาตสิทธิ์ Microphone เพื่อรับสัญญาณเสียงสดจาก Audio Interface"
                )
            }
        }
    }

    val audioFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("กำลังถอดรหัสไฟล์เสียงเพื่อนำเข้า DSP...")
            }
            viewModel.importTestAudioFile(uri) { result ->
                coroutineScope.launch {
                    result.onSuccess { name ->
                        snackbarHostState.showSnackbar("นำเข้าไฟล์ทดสอบ \"$name\" เรียบร้อย")
                    }.onFailure { err ->
                        snackbarHostState.showSnackbar(
                            "ไม่สามารถอ่านไฟล์เสียงได้: ${err.localizedMessage ?: "Unknown error"}"
                        )
                    }
                }
            }
        }
    }

    val requestMicAndStart: () -> Unit = {
        val currentGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        hasMicPermission = currentGranted
        if (currentGranted) {
            viewModel.startAudioEngine()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val pickTestAudioFile: () -> Unit = {
        audioFilePickerLauncher.launch(arrayOf("audio/*"))
    }

    val switchSourceMode: (AudioSourceMode) -> Unit = { mode ->
        when (mode) {
            AudioSourceMode.TEST_FILE -> {
                if (telemetry.testFileName == null) {
                    pickTestAudioFile()
                } else {
                    viewModel.switchAudioSourceMode(AudioSourceMode.TEST_FILE)
                    if (!telemetry.isRunning) {
                        viewModel.startAudioEngine()
                    }
                }
            }
            AudioSourceMode.LIVE_MIC -> {
                val currentGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                hasMicPermission = currentGranted
                viewModel.switchAudioSourceMode(AudioSourceMode.LIVE_MIC)
                if (!currentGranted) {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val navItems = remember {
        listOf(
            NavDestinationItem(
                route = ROUTE_KEY_DETECT,
                labelResId = R.string.nav_pedal,
                selectedIcon = Icons.Filled.GraphicEq,
                unselectedIcon = Icons.Outlined.GraphicEq,
                testTag = "nav_key_detect"
            ),
            NavDestinationItem(
                route = ROUTE_MONITOR,
                labelResId = R.string.nav_monitor,
                selectedIcon = Icons.Filled.Speed,
                unselectedIcon = Icons.Outlined.Speed,
                testTag = "nav_monitor"
            ),
            NavDestinationItem(
                route = ROUTE_WS,
                labelResId = R.string.nav_ws,
                selectedIcon = Icons.Filled.Wifi,
                unselectedIcon = Icons.Outlined.Wifi,
                testTag = "nav_ws"
            ),
            NavDestinationItem(
                route = ROUTE_PRESETS,
                labelResId = R.string.nav_setup,
                selectedIcon = Icons.Filled.Bookmark,
                unselectedIcon = Icons.Outlined.BookmarkBorder,
                testTag = "nav_presets"
            )
        )
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val onNavigate: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 640.dp

        Scaffold(
            containerColor = StageObsidian,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets.statusBars,
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = StageRackSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            val label = stringResource(item.labelResId)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onNavigate(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = TextPrimary,
                                    indicatorColor = if (item.route == ROUTE_WS) LedBlue else PedalCrimson,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = StageRackSurface,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        navItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                            val label = stringResource(item.labelResId)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { onNavigate(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Color.White,
                                    selectedTextColor = TextPrimary,
                                    indicatorColor = if (item.route == ROUTE_WS) LedBlue else PedalCrimson,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_KEY_DETECT
                    ) {
                        composable(ROUTE_KEY_DETECT) {
                            KeyDetectorScreen(
                                pedalState = pedalState,
                                telemetry = telemetry,
                                dispatchStatus = dispatchStatus,
                                wsStatus = wsStatus,
                                settings = settings,
                                hasMicPermission = hasMicPermission,
                                onRequestMicAndStart = requestMicAndStart,
                                onPickTestAudioFile = pickTestAudioFile,
                                onSwitchSourceMode = switchSourceMode,
                                onNavigateToWs = { onNavigate(ROUTE_WS) },
                                viewModel = viewModel
                            )
                        }
                        composable(ROUTE_MONITOR) {
                            MonitorDashboardScreen(
                                pedalState = pedalState,
                                telemetry = telemetry,
                                dispatchStatus = dispatchStatus,
                                usbDiagnostic = usbDiagnostic,
                                hasMicPermission = hasMicPermission,
                                onRequestMicAndStart = requestMicAndStart,
                                onPickTestAudioFile = pickTestAudioFile,
                                onSwitchSourceMode = switchSourceMode,
                                viewModel = viewModel
                            )
                        }
                        composable(ROUTE_WS) {
                            WebSocketControlScreen(
                                wsStatus = wsStatus,
                                dispatchStatus = dispatchStatus,
                                settings = settings,
                                wsLogs = wsLogs,
                                wsRules = wsRules,
                                presets = presets,
                                viewModel = viewModel
                            )
                        }
                        composable(ROUTE_PRESETS) {
                            PresetsAndSetupScreen(
                                pedalState = pedalState,
                                telemetry = telemetry,
                                presets = presets,
                                settings = settings,
                                inputDevices = inputDevices,
                                outputDevices = outputDevices,
                                usbDiagnostic = usbDiagnostic,
                                onPickTestAudioFile = pickTestAudioFile,
                                onSwitchSourceMode = switchSourceMode,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
