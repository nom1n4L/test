package com.skorsnap.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.Images
import com.skorsnap.app.data.MatchPrediction
import com.skorsnap.app.data.Outcome
import com.skorsnap.app.data.Report
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.Slip

@Composable
fun Card(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            content()
        }
    }
}

// --- Home --------------------------------------------------------------------

@Composable
fun HomeScreen(
    matches: List<MatchPrediction>,
    selected: Set<String>,
    hasKey: Boolean,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSlip: () -> Unit,
    onReport: () -> Unit,
    onSettings: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!hasKey) {
            item {
                Card(title = "Pasang kunci dulu", subtitle = "Sekali saja.") {
                    Text(
                        "Aplikasi ini membaca screenshot statistikmu lewat Gemini, jadi perlu " +
                            "kunci API dari aistudio.google.com. Gratis — cukup akun Google, " +
                            "tanpa kartu kredit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onSettings,
                        colors = ButtonDefaults.buttonColors(containerColor = Sky),
                    ) { Text("Buka Pengaturan") }
                }
            }
        }

        item {
            Button(
                onClick = onAdd,
                enabled = hasKey,
                colors = ButtonDefaults.buttonColors(containerColor = Sky),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("+  Tambah Pertandingan", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (matches.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada pertandingan.\n\n" +
                            "Buka aplikasi statistikmu, screenshot halaman pertandingan, " +
                            "lalu tekan tombol di atas. Boleh beberapa gambar untuk satu " +
                            "pertandingan.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            return@LazyColumn
        }

        if (selected.isNotEmpty()) {
            item {
                Button(
                    onClick = onSlip,
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Lihat parlay ${selected.size} pertandingan")
                }
            }
        }

        items(matches, key = { it.id }) { match ->
            MatchRow(match, match.id in selected, onOpen, onToggle)
        }

        item {
            Spacer(Modifier.height(6.dp))
            val settled = matches.count { it.settled }
            TextButton(onClick = onReport) {
                Text(
                    if (settled == 0) "Rapor akurasi" else "Rapor akurasi ($settled hasil)",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                "Centang pertandingan untuk menyusunnya jadi parlay. Tandai hasilnya " +
                    "di halaman tiap pertandingan setelah selesai main.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MatchRow(
    match: MatchPrediction,
    checked: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 4.dp, end = 13.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle(match.id) },
                colors = CheckboxDefaults.colors(checkedColor = Green),
            )
            Column(Modifier.weight(1f).clickable { onOpen(match.id) }) {
                Text(match.title, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (match.pick.isBlank()) match.league else "${match.pick} · ${match.league}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${match.pickPercent}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = probColor(match.pickProb),
                )
                when (match.outcome) {
                    Outcome.WON -> Text(
                        "tembus ✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = Green,
                    )
                    Outcome.LOST -> Text(
                        "meleset ✗",
                        style = MaterialTheme.typography.labelSmall,
                        color = Rose,
                    )
                    Outcome.PENDING -> if (match.thin) {
                        Text(
                            "data tipis",
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber,
                        )
                    }
                }
            }
        }
    }
}

// --- Add ---------------------------------------------------------------------

@Composable
fun AddScreen(
    staged: List<ByteArray>,
    busy: Boolean,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onAnalyse: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(
            title = "Screenshot Statistik",
            subtitle = "Boleh lebih dari satu gambar untuk satu pertandingan.",
        ) {
            Text(
                "Makin lengkap yang terlihat, makin baik hasilnya: form terakhir, rata-rata " +
                    "gol, head-to-head, tabel klasemen. Yang tidak ada di gambar tidak akan " +
                    "dipakai — dan akan disebutkan apa saja yang kurang.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPick,
                colors = ButtonDefaults.buttonColors(containerColor = Sky),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (staged.isEmpty()) "Pilih Gambar" else "Tambah Gambar Lagi")
            }
            val bands = remember(staged) { staged.sumOf { Images.forUpload(it).size } }
            if (bands > staged.size) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ada long capture di sini. Gambarnya dipotong jadi $bands bagian " +
                        "beresolusi penuh supaya angkanya tetap terbaca jelas — tidak " +
                        "dikecilkan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Sky,
                )
            }
        }

        if (staged.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                staged.forEachIndexed { index, bytes ->
                    Box {
                        val bitmap = remember(bytes) {
                            Images.preview(bytes)?.asImageBitmap()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.size(96.dp, 150.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Box(
                                Modifier.size(96.dp, 150.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                            shape = RoundedCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
                            modifier = Modifier.align(Alignment.TopStart).clickable { onRemove(index) },
                        ) {
                            Text(
                                "✕",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Rose,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Catatan (opsional)") },
                placeholder = { Text("mis. tim tuan rumah tanpa striker utamanya") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
                maxLines = 3,
            )

            Button(
                onClick = { onAnalyse(note) },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Membaca gambar…")
                    }
                } else {
                    Text("Analisa ${staged.size} Gambar", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

// --- Detail ------------------------------------------------------------------

@Composable
fun DetailScreen(
    match: MatchPrediction,
    onMark: (Outcome) -> Unit,
    onDelete: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Text(match.title, style = MaterialTheme.typography.titleLarge)
                if (match.league.isNotBlank()) {
                    Text(
                        match.league,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!match.readable && match.problem.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = Amber.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            match.problem,
                            modifier = Modifier.padding(11.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = Amber,
                        )
                    }
                }
            }
        }

        item {
            Card(title = "Rekomendasi", subtitle = "Dipilih dari market yang datanya ada di gambar.") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            match.pick.ifBlank { "—" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Pasang kalau odds-nya di atas %.2f".format(match.pickBreakEven),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${match.pickPercent}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = probColor(match.pickProb),
                    )
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.height(10.dp))
                Row {
                    Stat("Keyakinan", match.confidence.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                    Stat("Perkiraan gol", "%.1f - %.1f".format(match.xgHome, match.xgAway), Modifier.weight(1f))
                }
                if (match.confidenceWhy.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        match.confidenceWhy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card(title = "Hasil Akhir") {
                Bar("${match.home} menang", match.probHome)
                Bar("Seri", match.probDraw)
                Bar("${match.away} menang", match.probAway)
            }
        }

        if (match.markets.isNotEmpty()) {
            item {
                Card(title = "Semua Market", subtitle = "Angka di kanan adalah odds impasnya.") {
                    match.markets.forEachIndexed { i, m ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                if (m.why.isNotBlank()) {
                                    Text(
                                        m.why,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                "${m.percent}%",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = probColor(m.prob),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "%.2f".format(m.breakEven),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item { OutcomeCard(match, onMark) }

        item { StatsReadCard(match) }

        item {
            TextButton(onClick = onDelete) {
                Text("Hapus pertandingan ini", color = Rose, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * Records the result once the match has been played.
 *
 * Without this the app can only ever repeat what it predicted. Memory keeps the
 * winners and drops the rest, so the only way to know whether a stated 78% means
 * anything is to write down what happened while it is still known.
 */
@Composable
private fun OutcomeCard(match: MatchPrediction, onMark: (Outcome) -> Unit) {
    Card(
        title = "Sudah Main?",
        subtitle = "Tandai hasilnya supaya akurasi aslimu bisa dihitung.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutcomeButton(
                text = "Tembus",
                active = match.outcome == Outcome.WON,
                colour = Green,
                modifier = Modifier.weight(1f),
            ) { onMark(Outcome.WON) }
            OutcomeButton(
                text = "Meleset",
                active = match.outcome == Outcome.LOST,
                colour = Rose,
                modifier = Modifier.weight(1f),
            ) { onMark(Outcome.LOST) }
        }
        if (match.settled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tercatat. Tekan lagi tombol yang sama kalau salah tandai.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OutcomeButton(
    text: String,
    active: Boolean,
    colour: androidx.compose.ui.graphics.Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) colour.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            if (active) "$text ✓" else text,
            modifier = Modifier.padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) colour else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * What the analysis actually saw, and what it went looking for and could not find.
 * This is the check on the whole idea: without it there is no way to tell a number
 * read off the user's screenshot from one the model invented.
 */
@Composable
private fun StatsReadCard(match: MatchPrediction) {
    var open by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { open = !open },
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Yang Dibaca dari Gambarmu", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${match.statsSeen.size} statistik terbaca" +
                            if (match.statsMissing.isNotEmpty()) ", ${match.statsMissing.size} tidak ada" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (open) "−" else "+", style = MaterialTheme.typography.titleLarge)
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 10.dp)) {
                    match.statsSeen.forEach {
                        Text(
                            "✓  $it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                    if (match.statsMissing.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tidak ada di gambar — jadi tidak dipakai:",
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber,
                        )
                        match.statsMissing.forEach {
                            Text(
                                "–  $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Bar(label: String, prob: Double) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${Math.round(prob * 100)}%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = probColor(prob),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier.fillMaxWidth(prob.coerceIn(0.0, 1.0).toFloat()).height(5.dp)
                    .clip(RoundedCornerShape(3.dp)).background(probColor(prob))
            )
        }
    }
}

// --- Slip --------------------------------------------------------------------

@Composable
fun SlipScreen(slip: Slip, onOpen: (String) -> Unit, onClear: () -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (slip.size == 0) {
            item {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada yang dicentang.\n\nKembali ke daftar dan centang " +
                            "pertandingan yang mau digabung.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Card(title = "Parlay ${slip.size} Leg") {
                Row {
                    Stat("Tembus semua", "${slip.percent}%", Modifier.weight(1f))
                    Stat("Kira-kira", "1 dari ${slip.oneInN}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Bayaran wajar", "%.2f".format(slip.fairOdds), Modifier.weight(1f))
                    Stat("Setelah margin", "%.2f".format(slip.realisticOdds), Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Dari ${slip.size} leg ini, yang diperkirakan tembus sekitar " +
                        "%.1f — artinya sekitar %.1f diperkirakan meleset."
                            .format(slip.expectedHits, slip.size - slip.expectedHits),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (slip.weakLegs.isNotEmpty()) {
            item {
                Surface(
                    color = Amber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${slip.weakLegs.size} leg datanya tipis: " +
                            slip.weakLegs.joinToString { it.title } +
                            ". Screenshot yang lebih lengkap akan memperbaikinya.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
            }
        }

        items(slip.legs, key = { it.id }) { leg ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(leg.id) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(leg.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            leg.pick,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "${leg.pickPercent}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = probColor(leg.pickProb),
                    )
                }
            }
        }

        item { MarginTable(slip) }

        item {
            TextButton(onClick = onClear) {
                Text("Kosongkan pilihan", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The part that does not flatter the slip. Every leg multiplies the bookmaker's
 * margin as well as the risk, and the table makes that visible before the bet is
 * placed rather than after.
 */
@Composable
private fun MarginTable(slip: Slip) {
    Card(title = "Kenapa Leg Banyak Merugikan", subtitle = "Margin bandar ikut dikalikan.") {
        Text(
            "Peluang tiap leg dikalikan, bukan dirata-rata — dan margin bandar juga. " +
                "Margin per leg terukur 6,03% dari 7.314 harga 1X2 Bet365. Hasilnya, " +
                "imbal hasil harapan parlay tidak bergantung pada sebagus apa pilihanmu, " +
                "hanya pada berapa banyak leg-nya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Parlay.marginTable().forEach { (n, loss) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(
                    "$n leg",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (n == slip.size) FontWeight.Bold else FontWeight.Normal,
                    color = if (n == slip.size) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    "−$loss%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (n == slip.size) FontWeight.Bold else FontWeight.Normal,
                    color = Rose,
                )
            }
        }
    }
}

// --- Settings ----------------------------------------------------------------

@Composable
fun SettingsScreen(vm: AppViewModel) {
    var key by remember { mutableStateOf(vm.store.apiKey) }
    val available by vm.models.collectAsStateWithLifecycle()
    val modelsBusy by vm.modelsBusy.collectAsStateWithLifecycle()
    val report by vm.modelReport.collectAsStateWithLifecycle()
    var model by remember(available) { mutableStateOf(vm.store.model) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(title = "Kunci Gemini", subtitle = "Dibutuhkan untuk membaca gambar.") {
            Text(
                "Buka aistudio.google.com, masuk dengan akun Google, tekan \"Get API key\", " +
                    "lalu tempel kuncinya di sini.\n\n" +
                    "Gratis, tanpa kartu kredit. Ada batas pemakaian per menit dan per hari; " +
                    "kalau kena batas, aplikasi akan bilang dan kamu tinggal tunggu sebentar " +
                    "atau pilih model Flash yang jatahnya lebih besar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it.trim()
                    vm.setApiKey(key)
                },
                label = { Text("AQ.Ab8... atau AIza...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }

        Card(
            title = "Model",
            subtitle = "Nama model sering berubah dan berbeda tiap kunci — jadi daftarnya " +
                "diambil langsung dari Google, bukan ditebak.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.loadModels() },
                    enabled = !modelsBusy && key.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Sky),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (modelsBusy) "…" else "Muat daftar", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { vm.testModel() },
                    enabled = !modelsBusy && key.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = Green),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (modelsBusy) "…" else "Tes model ini", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Daftarnya gratis. \"Tes model ini\" mengirim satu kalimat ke model yang " +
                    "sedang dipilih — kalau ada yang salah, pesan asli dari Google akan muncul " +
                    "apa adanya.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (report != null) {
                Spacer(Modifier.height(10.dp))
                val bad = report!!.startsWith("Gagal") || report!!.contains("Kata Google")
                Surface(
                    color = (if (bad) Rose else Green).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        report!!,
                        modifier = Modifier.padding(11.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bad) Rose else Green,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            val options: List<Triple<String, String, String>> = if (available.isNotEmpty()) {
                available.map { Triple(it.label, it.id, it.description) }
            } else {
                Analyst.MODELS
            }
            if (available.isEmpty()) {
                Text(
                    "Daftar bawaan di bawah ini cuma tebakan. Kalau muncul error " +
                        "\"model tidak ada\", tekan tombol di atas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
                Spacer(Modifier.height(6.dp))
            }
            options.forEach { (label, id, note) ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        model = id
                        vm.setModel(id)
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = model == id,
                        onClick = {
                            model = id
                            vm.setModel(id)
                        },
                        colors = RadioButtonDefaults.colors(selectedColor = Sky),
                    )
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall)
                        Text(
                            // The description is often just the label again; the id
                            // is what the user needs when a model misbehaves.
                            if (note.isBlank() || note.equals(label, true)) id else note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Card(title = "Yang Perlu Kamu Tahu") {
            Text(
                "Aplikasi ini hanya memakai angka yang terlihat di screenshot-mu. Kalau " +
                    "sesuatu tidak ada di gambar, itu akan disebutkan, bukan dikarang — " +
                    "kamu bisa memeriksanya di bagian \"Yang Dibaca dari Gambarmu\" pada " +
                    "tiap pertandingan.\n\n" +
                    "Yang keluar adalah peluang, bukan kepastian. Peluang 80% tetap " +
                    "meleset 1 dari 5 kali, dan itu bukan tanda ada yang rusak.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --- Report ------------------------------------------------------------------

/**
 * How the picks have actually done, against what they promised.
 *
 * The point of the screen is the second number. A hit rate on its own invites the
 * reader to extrapolate a good run forwards; set beside the rate the app claimed,
 * it answers the only question that matters — whether the percentages can be
 * taken at face value.
 */
@Composable
fun ReportScreen(report: Report, onOpen: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (report.total == 0) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada hasil yang ditandai.\n\n" +
                            "Setelah pertandingan selesai, buka halamannya lalu tekan " +
                            "Tembus atau Meleset. Angka di sini akan terisi sendiri.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            return@LazyColumn
        }

        item {
            Card(title = "Rapor Akurasi", subtitle = "${report.total} hasil tercatat.") {
                Row {
                    Stat("Tembus", "${report.won}/${report.total}", Modifier.weight(1f))
                    Stat(
                        "Akurasi nyata",
                        "${Math.round(report.actual * 100)}%",
                        Modifier.weight(1f),
                    )
                    Stat(
                        "Yang dijanjikan",
                        "${Math.round(report.promised * 100)}%",
                        Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Bar("Akurasi nyata", report.actual)
                Bar("Dijanjikan model", report.promised)
            }
        }

        item {
            val good = kotlin.math.abs(report.gap) < 0.05 && report.meaningful
            Surface(
                color = (if (good) Green else Amber).copy(alpha = 0.12f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    report.verdict,
                    modifier = Modifier.padding(13.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (good) Green else Amber,
                )
            }
        }

        item {
            Card(
                title = "Seberapa Yakin Angka Ini",
                subtitle = "Sampel kecil membuat angka terlihat lebih pasti dari sebenarnya.",
            ) {
                Text(
                    "Dari ${report.total} hasil, akurasi sejatimu ada di antara " +
                        "${Math.round(report.low * 100)}% dan ${Math.round(report.high * 100)}% " +
                        "— ketelitiannya ±${report.precision} poin persen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (report.meaningful) {
                        "Jumlah ini sudah cukup untuk dipercaya."
                    } else {
                        "Kumpulkan sampai sekitar 50 hasil sebelum menyimpulkan apa pun, " +
                            "dan jaga ukuran taruhan tetap sama sampai saat itu. Kalau " +
                            "modelnya memang bagus, uangnya tetap ada nanti."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Text(
                "Riwayat",
                style = MaterialTheme.typography.labelSmall,
                color = Sky,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        items(report.settled.reversed(), key = { it.id }) { match ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { onOpen(match.id) },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(match.title, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${match.pick} · ${match.pickPercent}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        if (match.outcome == Outcome.WON) "tembus ✓" else "meleset ✗",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (match.outcome == Outcome.WON) Green else Rose,
                    )
                }
            }
        }
    }
}
