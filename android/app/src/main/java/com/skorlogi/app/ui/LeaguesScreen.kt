package com.skorlogi.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.League
import com.skorlogi.app.data.Leagues

@Composable
fun LeaguesScreen(
    initial: Set<String>,
    initialOpen: Set<String>,
    matchCounts: Map<String, Int>,
    onChange: (Set<String>) -> Unit,
    onChangeOpen: (Set<String>) -> Unit,
) {
    var selected by remember { mutableStateOf(initial) }
    var selectedOpen by remember { mutableStateOf(initialOpen) }

    fun update(next: Set<String>) {
        selected = next
        onChange(next)
    }

    fun updateOpen(next: Set<String>) {
        selectedOpen = next
        onChangeOpen(next)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionCard(
                title = "Liga yang Diikuti",
                subtitle = "${selected.size + selectedOpen.size} liga aktif. " +
                    "Mematikan liga yang tidak dipakai bikin proses perbarui lebih cepat.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { update(Leagues.ALL.map { it.code }.toSet()) }) {
                        Text("Pilih semua")
                    }
                    TextButton(onClick = { update(emptySet()) }) {
                        Text("Kosongkan")
                    }
                }
            }
        }

        item {
            GroupHeader("Sumber cadangan — tanpa kunci, jadwal semusim penuh")
            Text(
                "Dari arsip terbuka di GitHub. Tidak memuat data odds, jadi biasanya tetap " +
                    "bisa dibuka di jaringan yang memblokir sumber utama. Jadwalnya sampai " +
                    "akhir musim, bukan seminggu — tapi tanpa market corner dan kartu.\n\n" +
                    "Nyalakan ini kalau sumber utama diblokir. Kalau keduanya jalan, " +
                    "pertandingan yang sama akan muncul dua kali.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        items(Leagues.OPEN_LEAGUES, key = { it.code }) { l ->
            LeagueRow(l, l.code in selectedOpen, matchCounts[l.code] ?: 0) { on ->
                updateOpen(if (on) selectedOpen + l.code else selectedOpen - l.code)
            }
        }

        item { GroupHeader("Statistik lengkap — termasuk corner, kartu, dan babak 1") }
        items(Leagues.ALL.filter { it.hasRichStats }, key = { it.code }) { l ->
            LeagueRow(l, l.code in selected, matchCounts[l.code] ?: 0) { on ->
                update(if (on) selected + l.code else selected - l.code)
            }
        }

        item { GroupHeader("Gol dan odds saja — market corner dan kartu tidak tersedia") }
        items(Leagues.ALL.filter { !it.hasRichStats }, key = { it.code }) { l ->
            LeagueRow(l, l.code in selected, matchCounts[l.code] ?: 0) { on ->
                update(if (on) selected + l.code else selected - l.code)
            }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "Setelah mengubah pilihan, jalankan Perbarui Data di halaman Pengaturan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Green,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
}

@Composable
private fun LeagueRow(league: League, checked: Boolean, matches: Int, onToggle: (Boolean) -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable { onToggle(!checked) },
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = onToggle,
                colors = CheckboxDefaults.colors(checkedColor = Green),
            )
            Column(Modifier.weight(1f)) {
                Text(league.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    league.country,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (matches > 0) "$matches laga" else "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
