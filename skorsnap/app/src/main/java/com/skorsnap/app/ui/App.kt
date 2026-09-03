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
import com.skorsnap.app.data.Lens
import com.skorsnap.app.data.MarketOption
import com.skorsnap.app.data.Parlay

@Composable
fun App(vm: AppViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val matches by vm.matches.collectAsStateWithLifecycle()
    val staged by vm.staged.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val strategy by vm.strategy.collectAsStateWithLifecycle()
    val legOdds by vm.legOdds.collectAsStateWithLifecycle()
    val chosen by vm.chosen.collectAsStateWithLifecycle()
    val slips by vm.slips.collectAsStateWithLifecycle()
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
                    onHistory = { vm.go(Screen.History) },
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
                // Same screen, different destination: the images join an existing
                // analysis instead of starting a new one, so the mode is fixed to
                // whatever that match already is.
                is Screen.AddMore -> AddScreen(
                    staged = staged,
                    busy = busy,
                    mode = matches.firstOrNull { it.id == s.id }?.mode ?: mode,
                    onMode = {},
                    onPick = {
                        picker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemove = vm::removeStaged,
                    onAnalyse = { note -> vm.reanalyse(s.id, note) },
                    wanted = matches.firstOrNull { it.id == s.id }?.needMore ?: emptyList(),
                )
                is Screen.Detail -> {
                    // Looked up in the observed list, not fetched from the view
                    // model. vm.matchOf() read the flow's value directly, which
                    // Compose does not watch, so tapping Tembus saved the verdict
                    // but the colour only appeared after leaving and re-entering.
                    val match = matches.firstOrNull { it.id == s.id }
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
                            onMark = { lens, outcome -> vm.markOutcome(s.id, lens, outcome) },
                            onMarkMarket = { option, outcome ->
                                vm.markMarket(s.id, match.keyOf(option), outcome)
                            },
                            onBacked = { vm.setBacked(s.id, it) },
                            onAddMore = { vm.go(Screen.AddMore(s.id)) },
                            onDelete = { vm.remove(s.id) },
                        )
                    }
                }
                is Screen.Slip -> SlipScreen(
                    matches = matches.filter { it.id in selected },
                    strategy = strategy,
                    onStrategy = vm::setStrategy,
                    chosen = chosen,
                    onChoose = vm::chooseMarket,
                    onBest = { vm.takeBestPriced(matches.filter { it.id in selected }) },
                    onSave = vm::saveSlip,
                    odds = legOdds,
                    onOdds = vm::setLegOdds,
                    onOpen = { vm.go(Screen.Detail(it)) },
                    onClear = { vm.clearSelection() },
                )
                // Built from the observed match list rather than a plain call into
                // the view model. vm.report() read the flow's value directly, which
                // Compose does not watch, so recording a result while this screen
                // was open left the numbers on the previous total.
                is Screen.History -> HistoryScreen(
                    matches = matches,
                    onOpen = { vm.go(Screen.Detail(it)) },
                )
                is Screen.Report -> ReportScreen(
                    matches = matches,
                    slips = slips,
                    onMarkSlip = vm::markSlip,
                    onRemoveSlip = vm::removeSlip,
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
                        is Screen.AddMore -> "Tambah Data"
                        is Screen.Detail -> "Analisa"
                        is Screen.Slip -> "Parlay"
                        is Screen.History -> "Riwayat"
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
