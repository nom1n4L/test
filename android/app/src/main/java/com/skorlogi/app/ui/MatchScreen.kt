package com.skorlogi.app.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Leagues
import com.skorlogi.app.engine.Confidence
import com.skorlogi.app.engine.MarketGroup
import com.skorlogi.app.engine.Prediction
import com.skorlogi.app.engine.TeamForm
import kotlin.math.roundToInt

@Composable
fun MatchScreen(fixture: Fixture, prediction: Prediction?, predicting: Boolean) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Header(fixture, prediction) }

        if (prediction == null) {
            item {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    if (predicting) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Green)
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Melatih model untuk liga ini…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Text(
                            "Belum bisa diprediksi — riwayat salah satu tim tidak ada di data.\n" +
                                "Coba perbarui data di menu Pengaturan.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item { Headline(prediction) }

        if (prediction.values.isNotEmpty()) {
            item { ValueSection(prediction) }
        }

        item { ModelCompare(prediction) }

        if (prediction.homeForm != null && prediction.awayForm != null) {
            item { FormSection(prediction.homeForm, prediction.awayForm) }
        }

        if (prediction.h2h.isNotEmpty()) {
            item { H2HSection(prediction) }
        }

        items@ for (group in prediction.groups) {
            item(key = group.title) { MarketSection(group) }
        }

        item { Disclaimer() }
    }
}

@Composable
private fun Header(fixture: Fixture, prediction: Prediction?) {
    SectionCard(
        title = Leagues.byCode(fixture.league)?.label ?: fixture.league,
        subtitle = Dates.formatWithDay(fixture.dateEpochDay) +
            if (fixture.time.isNotEmpty()) " · ${fixture.time}" else "",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                fixture.home,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                "vs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            Text(
                fixture.away,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.End,
            )
        }
        if (prediction != null) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = prediction.confidence
                Chip(
                    "Keyakinan: ${c.label}",
                    color = when (c) {
                        Confidence.HIGH -> Green
                        Confidence.MEDIUM -> Amber
                        Confidence.LOW -> Rose
                    },
                    filled = true,
                )
                Chip("xG %.2f – %.2f".format(prediction.lambdaHome, prediction.lambdaAway))
            }
            Spacer(Modifier.height(6.dp))
            Text(
                prediction.confidence.note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Headline(p: Prediction) {
    SectionCard(
        title = "Prediksi Utama",
        subtitle = "Tebakan model: ${p.pick}",
    ) {
        TripleBar(p.pHome, p.pDraw, p.pAway)
        Spacer(Modifier.height(10.dp))
        Row {
            Stat(p.fixture.home, "${(p.pHome * 100).roundToInt()}%", Green, Modifier.weight(1f))
            Stat("Seri", "${(p.pDraw * 100).roundToInt()}%", MaterialTheme.colorScheme.onSurfaceVariant, Modifier.weight(1f))
            Stat(p.fixture.away, "${(p.pAway * 100).roundToInt()}%", Sky, Modifier.weight(1f))
        }
        val top = p.topScore
        if (top != null) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Skor paling mungkin",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${top.first} - ${top.second}",
                    style = MaterialTheme.typography.titleLarge,
                    color = Green,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(top.third * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

@Composable
private fun ValueSection(p: Prediction) {
    val best = p.values.first()
    // A big edge computed from a thin sample is noise, not an opportunity, and it is
    // exactly the case where the model looks most confident. Say so instead of
    // letting the number speak for itself.
    val trustworthy = p.confidence != Confidence.LOW
    SectionCard(
        title = "Perbandingan dengan Odds Bandar",
        subtitle = if (trustworthy) {
            "Selisih antara peluang model dan harga pasar."
        } else {
            "Riwayat salah satu tim terlalu tipis — angka selisih di bawah ini tidak bisa dipercaya."
        },
    ) {
        p.values.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(v.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    "odds %.2f".format(v.odds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    (if (v.edge >= 0) "+" else "") + "${v.edgePercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        !trustworthy -> MaterialTheme.colorScheme.onSurfaceVariant
                        v.edge > 0.03 -> Green
                        v.edge < -0.05 -> Rose
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !trustworthy ->
                    "Selisih sebesar apa pun di sini kemungkinan besar cuma kekurangan data, " +
                        "bukan peluang. Abaikan sampai kedua tim main lebih banyak."
                best.edge > 0.03 ->
                    "Model menilai \"${best.label}\" sedikit lebih murah dari harganya. " +
                        "Perlu diingat: pada uji ulang, harga bandar ternyata lebih akurat " +
                        "daripada model ini, jadi selisih kecil lebih sering berarti model yang " +
                        "keliru daripada harga yang salah."
                else ->
                    "Tidak ada selisih berarti. Harga pasar dan model kurang lebih sepakat."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelCompare(p: Prediction) {
    SectionCard(
        title = "Dua Model, Satu Pertandingan",
        subtitle = "Dixon–Coles memakai jumlah gol; Elo memakai hasil menang-seri-kalah.",
    ) {
        Row {
            Text("", Modifier.weight(1.4f))
            Text("Kandang", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text("Seri", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Text("Tandang", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(6.dp))
        CompareRow("Dixon–Coles", p.pHome, p.pDraw, p.pAway, true)
        CompareRow("Elo", p.eloProbs[0], p.eloProbs[1], p.eloProbs[2], false)
    }
}

@Composable
private fun CompareRow(label: String, h: Double, d: Double, a: Double, primary: Boolean) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
            color = if (primary) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf(h, d, a).forEach {
            Text(
                "${(it * 100).roundToInt()}%",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (primary) probColor(it) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FormSection(home: TeamForm, away: TeamForm) {
    SectionCard(title = "Performa Terakhir", subtitle = "Maksimal 10 laga terakhir, terbaru di kanan.") {
        FormBlock(home)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        FormBlock(away)
    }
}

@Composable
private fun FormBlock(f: TeamForm) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(f.team, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        FormPills(f.formString)
    }
    Spacer(Modifier.height(8.dp))
    Row {
        MiniStat("Cetak gol", "%.2f".format(f.avgScored), Modifier.weight(1f))
        MiniStat("Kebobolan", "%.2f".format(f.avgConceded), Modifier.weight(1f))
        MiniStat("BTTS", "${(f.bttsRate * 100).roundToInt()}%", Modifier.weight(1f))
        MiniStat("Over 2.5", "${(f.over25Rate * 100).roundToInt()}%", Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    Row {
        MiniStat("Elo", f.eloRating.roundToInt().toString(), Modifier.weight(1f))
        MiniStat("Poin", "${f.points}/${f.played * 3}", Modifier.weight(1f))
        MiniStat(
            "Corner",
            if (f.avgCorners < 0) "–" else "%.1f".format(f.avgCorners),
            Modifier.weight(1f),
        )
        MiniStat(
            "Kartu",
            if (f.avgCards < 0) "–" else "%.1f".format(f.avgCards),
            Modifier.weight(1f),
        )
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun H2HSection(p: Prediction) {
    val home = p.fixture.home
    val wins = p.h2h.count { (it.home == home && it.result == 'H') || (it.away == home && it.result == 'A') }
    val draws = p.h2h.count { it.result == 'D' }
    SectionCard(
        title = "Rekor Pertemuan",
        subtitle = "$home menang $wins, seri $draws, kalah ${p.h2h.size - wins - draws} dari ${p.h2h.size} laga terakhir.",
    ) {
        p.h2h.forEach { m ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Dates.formatShort(m.dateEpochDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(52.dp),
                )
                Text(m.home, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(5.dp),
                ) {
                    Text(
                        "${m.homeGoals} - ${m.awayGoals}",
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    m.away,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun MarketSection(group: MarketGroup) {
    // Long lists start collapsed so the page stays scannable.
    var expanded by remember(group.title) { mutableStateOf(group.lines.size <= 6) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium)
                    if (group.note != null) {
                        Text(
                            group.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    if (expanded) "−" else "+",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    val best = group.lines.maxByOrNull { it.prob }
                    group.lines.forEach { line ->
                        ProbRow(
                            label = line.label,
                            prob = line.prob,
                            trailing = "odds adil %.2f".format(line.fairOdds),
                            highlight = line === best,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Disclaimer() {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Semua angka di sini adalah peluang statistik dari data historis, bukan ramalan. " +
                "Pada uji ulang, tebakan hasil akhir benar sekitar 5 dari 10 kali. " +
                "Gunakan sebagai bahan analisis, bukan jaminan.",
            modifier = Modifier.padding(13.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
