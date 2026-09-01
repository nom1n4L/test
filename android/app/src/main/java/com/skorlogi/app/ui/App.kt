package com.skorlogi.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun App(vm: AppViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val prediction by vm.prediction.collectAsStateWithLifecycle()
    val predicting by vm.predicting.collectAsStateWithLifecycle()
    val quick by vm.quick.collectAsStateWithLifecycle()
    val picks by vm.picks.collectAsStateWithLifecycle()
    val tracked by vm.tracked.collectAsStateWithLifecycle()
    val trackerStats by vm.trackerStats.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val teamFixtures by vm.teamFixtures.collectAsStateWithLifecycle()
    val profileLoading by vm.profileLoading.collectAsStateWithLifecycle()
    val chatMessages by vm.chat.collectAsStateWithLifecycle()
    val chatBusy by vm.chatBusy.collectAsStateWithLifecycle()
    val parlayLegs by vm.parlayLegs.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        val m = state.message
        if (m != null) {
            snackbar.showSnackbar(m)
            vm.dismissMessage()
        }
    }

    BackHandler(enabled = screen !is Screen.Picks) { vm.back() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = { TopBar(screen, state, vm) },
        bottomBar = { BottomBar(screen, vm) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                is Screen.Fixtures -> FixturesScreen(
                    state = state,
                    quick = quick,
                    onOpen = vm::open,
                    onFilter = vm::setLeagueFilter,
                    onUseFallback = vm::useFallbackSource,
                    onOpenSettings = { vm.go(Screen.Settings) },
                )
                is Screen.Picks -> PicksScreen(
                    picks = picks,
                    working = state.syncing || (picks.isEmpty() && quick.isEmpty() && state.fixtures.isNotEmpty()),
                    blocked = state.blocked,
                    isFollowed = vm::isFollowed,
                    onFollow = { vm.follow(it) },
                    onOpen = vm::open,
                    onUseFallback = vm::useFallbackSource,
                    onOpenSettings = { vm.go(Screen.Settings) },
                )
                is Screen.Tracker -> TrackerScreen(
                    tracked = tracked,
                    stats = trackerStats,
                    onOdds = vm::setOdds,
                    onRemove = vm::unfollow,
                    onClearSettled = vm::clearSettled,
                )
                is Screen.Match -> MatchScreen(s.fixture, prediction, insights, predicting)
                is Screen.Chat -> ChatScreen(
                    messages = chatMessages,
                    busy = chatBusy,
                    hasKey = vm.repo.hasClaudeKey,
                    onSend = vm::sendChat,
                    onClear = vm::clearChat,
                    onOpenSettings = { vm.go(Screen.Settings) },
                )
                is Screen.Parlay -> ParlayScreen(
                    picks = picks,
                    legs = parlayLegs,
                    suggestions = remember(picks) { vm.parlaySuggestions() },
                    onToggle = vm::toggleParlayLeg,
                    onClear = vm::clearParlay,
                    build = vm::parlayOf,
                )
                is Screen.Search -> SearchScreen(
                    query = query,
                    results = results,
                    onQuery = vm::search,
                    onTeam = vm::openTeam,
                    onFixture = vm::open,
                )
                is Screen.Team -> TeamScreen(
                    teamName = s.team,
                    profile = profile,
                    fixtures = teamFixtures,
                    loading = profileLoading,
                    onFixture = vm::open,
                )
                is Screen.Leagues -> LeaguesScreen(
                    initial = vm.repo.enabledLeagues(),
                    initialOpen = vm.repo.enabledOpenLeagues(),
                    matchCounts = remember(state.matchCount) {
                        vm.repo.matches.groupingBy { it.league }.eachCount()
                    },
                    onChange = vm::setEnabledLeagues,
                    onChangeOpen = vm::setEnabledOpenLeagues,
                )
                is Screen.Settings -> SettingsScreen(
                    vm = vm,
                    state = state,
                    decay = vm.repo.decay,
                    onSync = vm::sync,
                    onDecay = vm::setDecay,
                    onOpenLeagues = { vm.go(Screen.Leagues) },
                )
            }
        }
    }
}

@Composable
private fun TopBar(screen: Screen, state: UiState, vm: AppViewModel) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (screen is Screen.Match || screen is Screen.Leagues || screen is Screen.Team) {
                    IconButton(onClick = vm::back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali", tint = MaterialTheme.colorScheme.onBackground)
                    }
                } else {
                    Box(Modifier.padding(start = 8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        when (val s = screen) {
                            is Screen.Picks -> "Skorlogi"
                            is Screen.Fixtures -> "Jadwal"
                            is Screen.Tracker -> "Pelacak"
                            is Screen.Search -> "Cari"
                            is Screen.Chat -> "Tanya Claude"
                            is Screen.Parlay -> "Parlay"
                            is Screen.Team -> s.team
                            is Screen.Match -> "Detail Prediksi"
                            is Screen.Leagues -> "Liga"
                            is Screen.Settings -> "Pengaturan"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    if (screen is Screen.Picks || screen is Screen.Fixtures) {
                        Text(
                            "${state.fixtures.size} pertandingan mendatang",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (screen !is Screen.Match && screen !is Screen.Team && screen !is Screen.Search) {
                    IconButton(onClick = { vm.go(Screen.Search) }) {
                        Icon(
                            Icons.Filled.Search,
                            "Cari",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (screen is Screen.Picks || screen is Screen.Fixtures) {
                    IconButton(onClick = vm::sync, enabled = !state.syncing) {
                        Icon(Icons.Filled.Refresh, "Perbarui", tint = Green)
                    }
                }
                if (screen !is Screen.Match && screen !is Screen.Team && screen !is Screen.Settings) {
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

@Composable
private fun BottomBar(screen: Screen, vm: AppViewModel) {
    if (screen is Screen.Match || screen is Screen.Team || screen is Screen.Search) return
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // Five is the most a bottom bar carries without becoming a wall of icons.
        // Search moved to the top bar, where it is reachable from every screen.
        val items = listOf(
            Triple("Pilihan", Icons.Filled.Star, Screen.Picks),
            Triple("Jadwal", Icons.Filled.DateRange, Screen.Fixtures),
            Triple("Parlay", Icons.Filled.ShoppingCart, Screen.Parlay),
            Triple("Tanya", Icons.Filled.Face, Screen.Chat),
            Triple("Pelacak", Icons.Filled.CheckCircle, Screen.Tracker),
        )
        items.forEach { (label, icon, target) ->
            NavigationBarItem(
                selected = screen::class == target::class,
                onClick = { vm.go(target) },
                icon = { Icon(icon, label) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Green,
                    selectedTextColor = Green,
                    indicatorColor = Green.copy(alpha = 0.14f),
                ),
            )
        }
    }
}
