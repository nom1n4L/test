package com.skorsnap.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun App(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val staged by vm.staged.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    // The system photo picker needs no storage permission and is available back to
    // API 19 through the backport, which is why it is used instead of a file dialog.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris -> vm.stage(uris) }

    LaunchedEffect(message) {
        val m = message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.dismissMessage()
        }
    }

    BackHandler(enabled = screen !is Screen.Home) { vm.back() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopBar(screen, vm) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                is Screen.Home -> HomeScreen(
                    matches = matches,
                    selected = selected,
                    hasKey = vm.store.hasKey,
                    onAdd = { vm.go(Screen.Add) },
                    onOpen = { vm.go(Screen.Detail(it)) },
                    onToggle = vm::toggle,
                    onSlip = { vm.go(Screen.Slip) },
                    onReport = { vm.go(Screen.Report) },
                    onSettings = { vm.go(Screen.Settings) },
                )
                is Screen.Add -> AddScreen(
                    staged = staged,
                    busy = busy,
                    mode = mode,
                    onMode = vm::setMode,
                    onPick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemove = vm::removeStaged,
                    onAnalyse = vm::analyse,
                )
                is Screen.Detail -> {
                    val match = vm.matchOf(s.id)
                    if (match == null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Pertandingan tidak ditemukan.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        DetailScreen(
                            match = match,
                            onMark = { vm.markOutcome(s.id, it) },
                            onBacked = { vm.setBacked(s.id, it) },
                            onDelete = { vm.remove(s.id) },
                        )
                    }
                }
                is Screen.Slip -> SlipScreen(
                    slip = vm.slip(),
                    onOpen = { vm.go(Screen.Detail(it)) },
                    onClear = { matches.forEach { m -> if (m.id in selected) vm.toggle(m.id) } },
                )
                is Screen.Report -> ReportScreen(
                    report = vm.report(),
                    onOpen = { vm.go(Screen.Detail(it)) },
                )
                is Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}

@Composable
private fun TopBar(screen: Screen, vm: AppViewModel) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (screen !is Screen.Home) {
                    IconButton(onClick = vm::back) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Kembali",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                } else {
                    Box(Modifier.padding(start = 10.dp))
                }
                Text(
                    when (screen) {
                        is Screen.Home -> "Skorsnap"
                        is Screen.Add -> "Tambah Pertandingan"
                        is Screen.Detail -> "Analisa"
                        is Screen.Slip -> "Parlay"
                        is Screen.Report -> "Rapor"
                        is Screen.Settings -> "Pengaturan"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                if (screen !is Screen.Settings) {
                    IconButton(onClick = { vm.go(Screen.Settings) }) {
                        Icon(
                            Icons.Filled.Settings,
                            "Pengaturan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
