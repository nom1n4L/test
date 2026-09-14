package com.skorlogi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Leagues

@Composable
fun SearchScreen(
    query: String,
    results: SearchResults,
    onQuery: (String) -> Unit,
    onTeam: (String, String) -> Unit,
    onFixture: (Fixture) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Cari tim, liga, atau pertandingan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )

        if (query.trim().length < 2) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Ketik minimal 2 huruf.\n\n" +
                        "Cari nama tim untuk melihat kekuatan, performa, dan jadwalnya. " +
                        "Cari nama liga untuk melihat semua pertandingannya.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(28.dp),
                )
            }
            return@Column
        }

        if (results.isEmpty) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Tidak ketemu.\n\nCoba ejaan lain, atau nama resminya — " +
                        "sumber datanya menulis \"Arsenal FC\", bukan \"Arsenal\".",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(28.dp),
                )
            }
            return@Column
        }

        LazyColumn(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 12.dp, end = 12.dp, bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (results.teams.isNotEmpty()) {
                item { Header("Tim (${results.teams.size})") }
                items(results.teams, key = { it.first + it.second }) { (team, league) ->
                    RowCard(
                        title = team,
                        subtitle = Leagues.label(league),
                        onClick = { onTeam(team, league) },
                    )
                }
            }
            if (results.fixtures.isNotEmpty()) {
                item { Header("Pertandingan mendatang (${results.fixtures.size})") }
                items(results.fixtures, key = { it.key }) { fx ->
                    RowCard(
                        title = "${fx.home} vs ${fx.away}",
                        subtitle = "${Leagues.label(fx.league)} · ${Dates.formatWithDay(fx.dateEpochDay)}" +
                            if (fx.time.isNotEmpty()) " ${fx.time}" else "",
                        onClick = { onFixture(fx) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Green,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
    )
}

@Composable
private fun RowCard(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
