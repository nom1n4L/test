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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import com.skorlogi.app.engine.TeamProfile
import kotlin.math.roundToInt

@Composable
fun TeamScreen(
    teamName: String,
    profile: TeamProfile?,
    fixtures: List<Pair<Fixture, DoubleArray?>>,
    loading: Boolean,
    onFixture: (Fixture) -> Unit,
) {
    if (profile == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(color = Green)
            } else {
                Text(
                    "Tim ini belum bisa dianalisis — riwayatnya tidak ada di data yang tersimpan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(28.dp),
                )
            }
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Overview(profile) }
        item { Strength(profile) }
        item { HomeAway(profile) }
        if (profile.form != null) item { Recent(profile) }
        if (fixtures.isNotEmpty()) item { Upcoming(teamName, fixtures, onFixture) }
    }
}

@Composable
private fun Overview(p: TeamProfile) {
    SectionCard(
        title = p.team,
        subtitle = Leagues.label(p.league),
    ) {
        Row {
            Stat("Peringkat", "#${p.rank}", "dari ${p.teamsInLeague} tim", Modifier.weight(1f))
            Stat("Elo", p.elo.roundToInt().toString(), "1500 = rata-rata", Modifier.weight(1f))
            Stat("Laga terpakai", p.matchesPlayed.toString(), "dalam model", Modifier.weight(1f))
        }
        if (!p.trustworthy) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Baru ${p.matchesPlayed} laga masuk hitungan. Semua angka di halaman ini " +
                    "masih ditarik kuat ke rata-rata liga, jadi bacalah sebagai perkiraan " +
                    "kasar — bukan gambaran kekuatan sebenarnya.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
    }
}

@Composable
private fun Strength(p: TeamProfile) {
    SectionCard(
        title = "Kekuatan",
        subtitle = "Dibandingkan tim rata-rata di liga yang sama.",
    ) {
        FactorBar("Serangan", p.attackFactor, higherIsBetter = true)
        Spacer(Modifier.height(10.dp))
        FactorBar("Pertahanan", p.defenceFactor, higherIsBetter = false)
        Spacer(Modifier.height(10.dp))
        Text(
            "Serangan %.2f berarti mencetak %s gol dari tim rata-rata. ".format(
                p.attackFactor,
                if (p.attackFactor >= 1) "%.0f%% lebih banyak".format((p.attackFactor - 1) * 100)
                else "%.0f%% lebih sedikit".format((1 - p.attackFactor) * 100),
            ) +
                "Pertahanan %.2f berarti kebobolan %s.".format(
                    p.defenceFactor,
                    if (p.defenceFactor <= 1) "%.0f%% lebih sedikit".format((1 - p.defenceFactor) * 100)
                    else "%.0f%% lebih banyak".format((p.defenceFactor - 1) * 100),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A bar centred on 1.0, so above and below average read at a glance. */
@Composable
private fun FactorBar(label: String, factor: Double, higherIsBetter: Boolean) {
    val good = if (higherIsBetter) factor > 1.0 else factor < 1.0
    val magnitude = (kotlin.math.abs(factor - 1.0) / 0.6).coerceIn(0.0, 1.0)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp),
        )
        Box(Modifier.weight(1f)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .padding(end = 0.dp)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                ) {}
                Surface(
                    color = if (good) Green else Rose,
                    shape = RoundedCornerShape(3.dp),
                    modifier = Modifier
                        .fillMaxWidth(magnitude.toFloat().coerceAtLeast(0.03f))
                        .height(6.dp),
                ) {}
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "%.2f".format(factor),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (good) Green else Rose,
        )
    }
}

@Composable
private fun HomeAway(p: TeamProfile) {
    SectionCard(
        title = "Kandang vs Tandang",
        subtitle = "Dua wajah yang sering berbeda jauh.",
    ) {
        Row {
            Text("", Modifier.weight(1.2f))
            Text("Main", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text("Cetak", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text("Kebobolan", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        SplitRow("Kandang", p.homePlayed, p.homeScored, p.homeConceded)
        Spacer(Modifier.height(4.dp))
        SplitRow("Tandang", p.awayPlayed, p.awayScored, p.awayConceded)
    }
}

@Composable
private fun SplitRow(label: String, played: Int, scored: Double, conceded: Double) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.2f), style = MaterialTheme.typography.bodyMedium)
        Text(
            played.toString(),
            Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "%.2f".format(scored),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Green,
        )
        Text(
            "%.2f".format(conceded),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Rose,
        )
    }
}

@Composable
private fun Recent(p: TeamProfile) {
    val form = p.form!!
    SectionCard(
        title = "Laga Terakhir",
        subtitle = "${form.points} poin dari ${form.played} laga. Terbaru di kanan.",
    ) {
        FormPills(form.formString)
        Spacer(Modifier.height(12.dp))
        form.entries.take(8).forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Dates.formatShort(e.match.dateEpochDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                )
                Text(
                    (if (e.isHome) "vs " else "di ") + e.opponent,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${e.scored} - ${e.conceded}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = when (e.outcome) {
                        'M' -> Green
                        'S' -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Rose
                    },
                )
            }
        }
    }
}

@Composable
private fun Upcoming(
    team: String,
    fixtures: List<Pair<Fixture, DoubleArray?>>,
    onFixture: (Fixture) -> Unit,
) {
    SectionCard(
        title = "Jadwal Berikutnya",
        subtitle = "Persentase adalah peluang menang $team menurut model.",
    ) {
        fixtures.forEachIndexed { i, (fx, probs) ->
            if (i > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
            }
            val isHome = fx.home == team
            val win = probs?.let { if (isHome) it[0] else it[2] }
            Row(
                Modifier.fillMaxWidth().clickable { onFixture(fx) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (isHome) "vs " else "di ") + (if (isHome) fx.away else fx.home),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        Dates.formatWithDay(fx.dateEpochDay) +
                            if (fx.time.isNotEmpty()) " · ${fx.time}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (win != null) {
                    Text(
                        "${(win * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = probColor(win),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, hint: String, modifier: Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(
            hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
