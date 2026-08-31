package com.skorlogi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Leagues
import kotlin.math.roundToInt

@Composable
fun FixturesScreen(
    state: UiState,
    quick: Map<String, DoubleArray>,
    onOpen: (Fixture) -> Unit,
    onFilter: (String?) -> Unit,
) {
    val shown = state.fixtures.filter { state.leagueFilter == null || it.league == state.leagueFilter }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Green)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (state.syncing) {
            val p = state.progress
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(
                    p?.let { "Mengunduh ${it.current}  (${it.done}/${it.total})" } ?: "Mengunduh data…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (p != null && p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.done.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth(),
                        color = Green,
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = Green)
                }
            }
        }

        LeagueFilterRow(state, onFilter)

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.matchCount == 0) "Belum ada data." else "Tidak ada jadwal untuk filter ini.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tarik ke bawah atau tekan tombol perbarui di atas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Column
        }

        val grouped = shown.groupBy { it.dateEpochDay }.toSortedMap()

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, top = 6.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            grouped.forEach { (day, list) ->
                item(key = "h$day") {
                    Text(
                        Dates.formatWithDay(day),
                        style = MaterialTheme.typography.labelSmall,
                        color = Green,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                }
                items(list, key = { it.key }) { fx ->
                    FixtureCard(fx, quick[fx.key], onOpen)
                }
            }
        }
    }
}

@Composable
private fun LeagueFilterRow(state: UiState, onFilter: (String?) -> Unit) {
    val codes = state.fixtures.map { it.league }.distinct().sorted()
    if (codes.size < 2) return
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterPill("Semua", state.leagueFilter == null) { onFilter(null) }
        codes.forEach { c ->
            val l = Leagues.byCode(c)
            FilterPill(l?.name ?: c, state.leagueFilter == c) { onFilter(c) }
        }
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) Green.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Green else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FixtureCard(fx: Fixture, probs: DoubleArray?, onOpen: (Fixture) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable { onOpen(fx) },
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Leagues.byCode(fx.league)?.label ?: fx.league,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (fx.time.isNotEmpty()) {
                    Text(
                        fx.time,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    fx.home,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "vs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Text(
                    fx.away,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
            Spacer(Modifier.height(9.dp))
            if (probs != null) {
                TripleBar(probs[0], probs[1], probs[2])
                Spacer(Modifier.height(5.dp))
                Row {
                    Text(
                        "${(probs[0] * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Green,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Seri ${(probs[1] * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${(probs[2] * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = Sky,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        Modifier.width(11.dp).height(11.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "Menghitung prediksi…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
