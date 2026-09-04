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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
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
import com.skorlogi.app.engine.Parlay
import com.skorlogi.app.engine.ParlayOption
import com.skorlogi.app.engine.Pick

@Composable
fun ParlayScreen(
    picks: List<Pick>,
    legs: List<Pick>,
    suggestions: List<ParlayOption>,
    onToggle: (Pick) -> Unit,
    onClear: () -> Unit,
    build: (List<Pick>) -> ParlayOption,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TheArithmetic() }

        if (legs.isNotEmpty()) {
            item { Slip(build(legs), onClear) }
        }

        if (picks.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada pilihan hari ini untuk disusun.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@LazyColumn
        }

        if (suggestions.isNotEmpty()) {
            item { Suggestions(suggestions) }
        }

        item {
            Text(
                "Susun sendiri — centang untuk menambahkan",
                style = MaterialTheme.typography.labelSmall,
                color = Green,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(picks, key = { "${it.fixture.key}|${it.kind.name}" }) { pick ->
            LegRow(
                pick = pick,
                checked = legs.any { it.fixture.key == pick.fixture.key && it.kind == pick.kind },
                onToggle = { onToggle(pick) },
            )
        }
    }
}

/**
 * The part that matters most on this screen. Presented first, before any slip, so
 * it is read rather than scrolled past.
 */
@Composable
private fun TheArithmetic() {
    SectionCard(
        title = "Sebelum Menyusun Parlay",
        subtitle = "Dua hitungan yang menentukan segalanya.",
    ) {
        Text(
            "1. Peluang dikalikan, bukan dirata-rata. Empat leg yang masing-masing " +
                "80% bukan 80%, tapi 41%. Bayaran naik, tapi peluangnya turun lebih cepat.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "2. Margin bandar juga dikalikan. Dari 7.314 harga 1X2 Bet365 di data " +
                "aplikasi ini, margin per leg terukur 6,03%. Hitung sampai habis, " +
                "imbal hasil harapan parlay n-leg adalah 1 ÷ 1,0603ⁿ — dan itu " +
                "tidak bergantung pada sebagus apa pilihanmu.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))
        Row {
            Text("Jumlah leg", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(
                "Rugi rata-rata",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(4.dp))
        Parlay.marginTable().forEach { (legs, loss) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text("$legs leg", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(
                    "−$loss%",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Rose,
                    textAlign = TextAlign.End,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Itulah sebabnya tidak ada parlay yang benar-benar aman — yang ada cuma " +
                "yang lebih lambat habisnya. Menambah leg selalu memperburuk harapan " +
                "matematisnya. Kalau tetap mau jalan, silakan; angka di bawah ini " +
                "apa adanya, supaya keputusannya diambil dengan mata terbuka.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Slip(option: ParlayOption, onClear: () -> Unit) {
    SectionCard(
        title = "Slip Kamu (${option.size} leg)",
        subtitle = option.legs.joinToString(" + ") { it.selection },
    ) {
        Row {
            Metric("Peluang tembus", "${option.percent}%", Modifier.weight(1f), probColor(option.combinedProb))
            Metric("Kira-kira", "1 dari ${option.oneInN}", Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row {
            Metric("Bayaran wajar", "%.2f".format(option.fairOdds), Modifier.weight(1f))
            Metric("Setelah margin", "%.2f".format(option.realisticOdds), Modifier.weight(1f))
        }
        val realPayout = option.quotedPayout
        val realReturn = option.quotedExpectedReturn
        if (realPayout != null && realReturn != null) {
            Spacer(Modifier.height(12.dp))
            Row {
                Metric("Bayaran nyata", "%.2f".format(realPayout), Modifier.weight(1f), Green)
                Metric(
                    "Harapan nyata",
                    "%.0f%%".format(realReturn * 100),
                    Modifier.weight(1f),
                    if (realReturn >= 1.0) Green else Rose,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Dihitung dari harga terbaik yang benar-benar ditawarkan bandar untuk tiap " +
                    "leg — bukan dari rata-rata margin.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        Surface(
            color = if (realReturn != null && realReturn >= 1.0) {
                Green.copy(alpha = 0.12f)
            } else {
                Rose.copy(alpha = 0.12f)
            },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    realReturn != null && realReturn >= 1.0 ->
                        "Dengan harga ini imbal hasil harapannya %.0f%% — di atas modal. " +
                            "Itu jarang, dan datangnya dari harga yang bagus, bukan dari " +
                            "prediksi yang bagus.".format(realReturn * 100)
                    realReturn != null ->
                        "Imbal hasil harapan %.0f%% dengan harga nyata — rata-rata rugi %d%%."
                            .format(realReturn * 100, ((1 - realReturn) * 100).toInt())
                    else ->
                        "Imbal hasil harapan: %.0f%% dari taruhanmu — rata-rata rugi %d%% tiap kali dipasang."
                            .format(option.expectedReturn * 100, option.expectedLossPercent)
                },
                modifier = Modifier.padding(11.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (realReturn != null && realReturn >= 1.0) Green else Rose,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onClear) { Text("Kosongkan slip") }
    }
}

@Composable
private fun Suggestions(options: List<ParlayOption>) {
    SectionCard(
        title = "Kalau Disusun dari yang Terkuat",
        subtitle = "Diambil dari pilihan berpeluang tertinggi, satu per pertandingan.",
    ) {
        options.forEachIndexed { i, o ->
            if (i > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${o.size} leg",
                    Modifier.width(58.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Tembus semua ${o.percent}% · 1 dari ${o.oneInN}",
                        style = MaterialTheme.typography.bodySmall,
                        color = probColor(o.combinedProb),
                    )
                    Text(
                        "Bayaran %.2f · harapan %.0f%%".format(o.realisticOdds, o.expectedReturn * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "−${o.expectedLossPercent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Rose,
                )
            }
        }
    }
}

@Composable
private fun LegRow(pick: Pick, checked: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = Green),
            )
            Column(Modifier.weight(1f)) {
                Text(pick.selection, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${pick.fixture.home} vs ${pick.fixture.away} · " +
                        Dates.formatShort(pick.fixture.dateEpochDay),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${pick.percent}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = probColor(pick.prob),
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    modifier: Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
