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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.OddsApi
import com.skorlogi.app.data.OddsEvent
import com.skorlogi.app.data.OddsQuota
import com.skorlogi.app.engine.PricedEdge
import com.skorlogi.app.engine.Value

@Composable
fun OddsScreen(
    events: List<OddsEvent>,
    edges: List<Pair<OddsEvent, PricedEdge>>,
    quota: OddsQuota?,
    busy: Boolean,
    message: String?,
    hasKey: Boolean,
    myBookmaker: String,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!hasKey) {
        NoOddsKey(onOpenSettings)
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Explainer(quota, busy, message, onRefresh) }

        if (edges.isNotEmpty()) {
            item { EdgeHeader(edges.size) }
            items(edges.take(25), key = { "${it.first.id}|${it.second.selection.label}|${it.second.best.bookmakerKey}" }) {
                EdgeCard(it.first, it.second)
            }
        } else if (events.isNotEmpty()) {
            item { NoEdges() }
        }

        if (events.isNotEmpty()) {
            item {
                Text(
                    "Semua pertandingan berharga (${events.size})",
                    style = MaterialTheme.typography.labelSmall,
                    color = Green,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
            items(events, key = { it.id }) { event ->
                EventCard(event, myBookmaker)
            }
        }
    }
}

@Composable
private fun Explainer(quota: OddsQuota?, busy: Boolean, message: String?, onRefresh: () -> Unit) {
    SectionCard(
        title = "Harga, Bukan Ramalan",
        subtitle = "Satu-satunya keunggulan yang bisa dibuktikan tanpa menebak.",
    ) {
        Text(
            "Model aplikasi ini kalah dari harga bandar di semua market yang diuji, jadi " +
                "mencari peluang dengan menebak cuma memunculkan kesalahan sendiri. " +
                "Halaman ini bekerja lain: ia membandingkan harga antar bandar.\\n\\n" +
                "1. Taruhan yang sama, harga berbeda. Ambil yang tertinggi — itu untung " +
                "pasti, tanpa perlu benar sekali pun.\\n\\n" +
                "2. Pinnacle dipakai sebagai patokan. Marginnya paling tipis dan harganya " +
                "bergerak mengikuti uang, bukan opini, jadi harganya adalah perkiraan " +
                "publik terbaik atas apa yang benar-benar akan terjadi. Kalau bandar lain " +
                "membayar lebih tinggi dari itu, selisihnya nyata.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onRefresh,
            enabled = !busy,
            colors = ButtonDefaults.buttonColors(containerColor = Green),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Mengambil…" else "Ambil Odds Terbaru")
        }
        if (quota != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Kuota bulan ini: ${quota.remaining} tersisa, ${quota.used} terpakai. " +
                    "Sekali ambil memakai ${quota.lastCost}.",
                style = MaterialTheme.typography.labelSmall,
                color = if (quota.remaining < 50) Amber else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = if (message.startsWith("Gagal")) Rose else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EdgeHeader(count: Int) {
    Text(
        "Harga di atas wajar ($count)",
        style = MaterialTheme.typography.labelSmall,
        color = Green,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun NoEdges() {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            "Tidak ada harga yang cukup menonjol dari patokan Pinnacle hari ini.\n\n" +
                "Itu hasil yang normal, bukan kegagalan — sebagian besar waktu para bandar " +
                "sepakat. Mengambil harga tertinggi di daftar bawah tetap menguntungkan " +
                "walau tidak ada yang ditandai di sini.",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EdgeCard(event: OddsEvent, edge: PricedEdge) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${event.home} vs ${event.away}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    Dates.formatShort(event.commenceEpochDay) + " " + event.commenceTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        edge.selection.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        OddsApi.MARKET_LABELS[edge.selection.market] ?: edge.selection.market,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "+${edge.edgePercent}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = Green,
                    )
                    Text(
                        "unggul",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            Row {
                Cell("Pasang di", edge.best.bookmaker, Modifier.weight(1.2f))
                Cell("Harganya", "%.2f".format(edge.best.price), Modifier.weight(1f), Green)
                Cell("Harga wajar", "%.2f".format(edge.fairOdds), Modifier.weight(1f))
                Cell("Peluang", "${edge.sharpPercent}%", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EventCard(event: OddsEvent, myBookmaker: String) {
    val comparisons = remember(event, myBookmaker) { Value.marginComparison(event, myBookmaker) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Text("${event.home} vs ${event.away}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "${Dates.formatShort(event.commenceEpochDay)} ${event.commenceTime} · " +
                    "${event.bookmakerCount} bandar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val h2h = event.market("h2h")
            if (h2h.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                h2h.forEach { selection ->
                    val best = selection.best
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            selection.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (best != null) {
                            Text(
                                best.bookmaker,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "%.2f".format(best.price),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Green,
                            )
                        }
                    }
                }
            }

            val h2hMargin = comparisons.firstOrNull { it.market == "h2h" }
            if (h2hMargin != null && h2hMargin.saved > 0) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = Green.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Bertahan di satu bandar: margin ${h2hMargin.singlePercent}%. " +
                            "Kejar harga terbaik: ${h2hMargin.bestPercent}%. " +
                            "Hemat ${h2hMargin.saved} poin, tanpa menebak apa pun.",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Green,
                    )
                }
            }
        }
    }
}

@Composable
private fun Cell(
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
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun NoOddsKey(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Butuh kunci the-odds-api", style = MaterialTheme.typography.titleMedium, color = Amber)
            Spacer(Modifier.height(10.dp))
            Text(
                "Daftar gratis di the-odds-api.com — email saja, 500 permintaan per bulan.\n\n" +
                    "Dari situ aplikasi bisa membaca harga 1xBet, Marathonbet, Betsson, " +
                    "William Hill, Pinnacle dan puluhan bandar lain sekaligus, lalu " +
                    "menunjukkan siapa yang membayar paling tinggi.\n\n" +
                    "Ini bagian aplikasi yang keunggulannya bisa dibuktikan matematis, " +
                    "bukan diperkirakan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(onClick = onOpenSettings, colors = ButtonDefaults.buttonColors(containerColor = Green)) {
                Text("Buka Pengaturan")
            }
        }
    }
}
