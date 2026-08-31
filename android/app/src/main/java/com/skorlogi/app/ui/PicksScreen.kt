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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Leagues
import com.skorlogi.app.engine.Confidence
import com.skorlogi.app.engine.Pick

@Composable
fun PicksScreen(
    picks: List<Pick>,
    working: Boolean,
    isFollowed: (Pick) -> Boolean,
    onFollow: (Pick) -> Unit,
    onOpen: (Fixture) -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Explainer() }

        if (picks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    if (working) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Green)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Menghitung…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            "Belum ada satu pun pertandingan yang lolos saringan.\n\n" +
                                "Itu hasil yang wajar: saringannya menuntut peluang minimal 68% " +
                                "dari market yang terbukti jujur, dan riwayat kedua tim harus cukup. " +
                                "Hari yang sepi memang bisa kosong.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }

        items(picks, key = { "${it.fixture.key}|${it.kind.name}" }) { pick ->
            PickCard(pick, isFollowed(pick), onFollow, onOpen)
        }

        if (picks.isNotEmpty()) {
            item { Footnote(picks) }
        }
    }
}

@Composable
private fun Explainer() {
    SectionCard(
        title = "Pilihan Terbaik Hari Ini",
        subtitle = "Diurutkan dari yang paling bisa dipercaya, bukan dari yang paling besar bayarannya.",
    ) {
        Text(
            "Daftar ini sengaja pelit. Dari semua market yang bisa dihitung aplikasi ini, " +
                "hanya empat yang lolos uji kejujuran — Double Chance, Hasil Akhir, Total Gol, " +
                "dan Babak 1 — karena saat diuji ulang, angka yang mereka sebut memang " +
                "kira-kira sebesar itu kejadiannya.\n\n" +
                "Corner dan kartu tidak pernah muncul di sini. Saat diuji, prediksi corner " +
                "yang mengaku 70%+ ternyata cuma benar sekitar setengahnya — jadi menariknya " +
                "menipu, dan sudah saya keluarkan dari daftar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PickCard(
    pick: Pick,
    followed: Boolean,
    onFollow: (Pick) -> Unit,
    onOpen: (Fixture) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Leagues.byCode(pick.fixture.league)?.label ?: pick.fixture.league,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Dates.formatShort(pick.fixture.dateEpochDay) +
                        if (pick.fixture.time.isNotEmpty()) " · ${pick.fixture.time}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(5.dp))
            Text(
                "${pick.fixture.home} vs ${pick.fixture.away}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { onOpen(pick.fixture) },
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        pick.selection,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${pick.market} · odds adil %.2f".format(pick.fairOdds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${pick.percent}%",
                    style = MaterialTheme.typography.titleLarge,
                    color = probColor(pick.prob),
                )
            }
            Spacer(Modifier.height(8.dp))
            ProbRow(label = "Peluang menurut model", prob = pick.prob)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip(
                    if (pick.confidence == Confidence.HIGH) "Data tebal" else "Data cukup",
                    color = if (pick.confidence == Confidence.HIGH) Green else Amber,
                    filled = true,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    pick.reliability,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { onFollow(pick) }, enabled = !followed) {
                Text(if (followed) "Sudah dicatat ✓" else "Catat di pelacak")
            }
        }
    }
}

@Composable
private fun Footnote(picks: List<Pick>) {
    val expected = picks.sumOf { it.prob }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Kalau kamu ikuti semua ${picks.size} pilihan di atas, secara statistik " +
                "yang diperkirakan benar sekitar ${Math.round(expected)} dari ${picks.size}. " +
                "Artinya sekitar ${picks.size - Math.round(expected)} memang diperkirakan meleset — " +
                "itu bagian dari perhitungannya, bukan tanda modelnya rusak.\n\n" +
                "Kalau semuanya digabung jadi satu parlay, peluang semuanya benar sekaligus " +
                "jauh lebih kecil daripada angka mana pun di atas.",
            modifier = Modifier.padding(13.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
