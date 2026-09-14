package com.skorlogi.app.ui

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Outcome
import com.skorlogi.app.data.TrackedPick
import com.skorlogi.app.data.TrackerStats
import kotlin.math.roundToInt

@Composable
fun TrackerScreen(
    tracked: List<TrackedPick>,
    stats: TrackerStats,
    onOdds: (String, Double) -> Unit,
    onRemove: (String) -> Unit,
    onClearSettled: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Scoreboard(stats, onClearSettled) }

        if (tracked.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada yang dicatat.\n\n" +
                            "Buka Pilihan Terbaik, lalu tekan \"Catat di pelacak\" pada prediksi " +
                            "yang kamu ikuti. Hasilnya diisi otomatis begitu pertandingannya selesai " +
                            "dan datanya masuk.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
        }

        items(tracked, key = { it.id }) { t ->
            TrackedCard(t, onOdds, onRemove)
        }
    }
}

@Composable
private fun Scoreboard(stats: TrackerStats, onClearSettled: () -> Unit) {
    SectionCard(
        title = "Rapor Prediksi",
        subtitle = if (stats.settled == 0) {
            "Belum ada yang selesai. Angka muncul setelah pertandingan berjalan."
        } else {
            "${stats.settled} selesai, ${stats.pending} masih berjalan."
        },
    ) {
        if (stats.settled > 0) {
            Row {
                Metric("Tembus", "${stats.won}/${stats.settled}", Modifier.weight(1f))
                Metric(
                    "Akurasi nyata",
                    "${(stats.hitRate * 100).roundToInt()}%",
                    Modifier.weight(1f),
                    probColor(stats.hitRate),
                )
                Metric(
                    "Yang dijanjikan",
                    "${(stats.expected * 100).roundToInt()}%",
                    Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
            val gap = stats.vsExpected
            Text(
                when {
                    stats.settled < 20 ->
                        "Sampelnya masih ${stats.settled}. Di bawah 20-an, selisih apa pun " +
                            "antara akurasi nyata dan yang dijanjikan masih wajar karena keberuntungan."
                    gap > 0.05 ->
                        "Sejauh ini hasilnya di atas yang dijanjikan model. Menyenangkan, " +
                            "tapi jangan buru-buru menyimpulkan — beruntung dan pintar terlihat sama " +
                            "di sampel kecil."
                    gap < -0.05 ->
                        "Hasilnya di bawah yang dijanjikan model. Kalau ini bertahan sampai " +
                            "50+ prediksi, berarti modelnya memang terlalu percaya diri untuk " +
                            "liga yang kamu ikuti."
                    else ->
                        "Akurasi nyata kurang lebih sama dengan yang dijanjikan model. " +
                            "Itu artinya angka persennya bisa dipercaya apa adanya."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (stats.hasMoney) {
                Spacer(Modifier.height(12.dp))
                Row {
                    Metric("Taruhan terisi odds", "${stats.staked.roundToInt()}", Modifier.weight(1f))
                    Metric(
                        "Untung/rugi",
                        "%+.2f".format(stats.profit),
                        Modifier.weight(1f),
                        if (stats.profit >= 0) Green else Rose,
                    )
                    Metric(
                        "ROI",
                        "%+.0f%%".format(stats.roi * 100),
                        Modifier.weight(1f),
                        if (stats.roi >= 0) Green else Rose,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Dihitung dengan asumsi taruhan 1 satuan per prediksi, hanya untuk yang " +
                        "kamu isi odds-nya.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onClearSettled) { Text("Hapus yang sudah selesai") }
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

@Composable
private fun TrackedCard(t: TrackedPick, onOdds: (String, Double) -> Unit, onRemove: (String) -> Unit) {
    var odds by remember(t.id) { mutableStateOf(if (t.odds > 1.0) "%.2f".format(t.odds) else "") }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${t.home} vs ${t.away}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                OutcomeBadge(t.outcome)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                "${t.selection}  ·  ${t.market}  ·  ${Dates.formatShort(t.dateEpochDay)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Model: ${(t.prob * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = probColor(t.prob),
                )
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = odds,
                    onValueChange = { text ->
                        odds = text
                        text.replace(',', '.').toDoubleOrNull()?.let { onOdds(t.id, it) }
                    },
                    label = { Text("Odds", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(110.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onRemove(t.id) }) { Text("Hapus") }
            }
        }
    }
}

@Composable
private fun OutcomeBadge(outcome: Outcome) {
    val (text, color) = when (outcome) {
        Outcome.WON -> "Tembus" to Green
        Outcome.LOST -> "Meleset" to Rose
        Outcome.VOID -> "Batal" to MaterialTheme.colorScheme.onSurfaceVariant
        Outcome.PENDING -> "Berjalan" to Amber
    }
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(6.dp)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
