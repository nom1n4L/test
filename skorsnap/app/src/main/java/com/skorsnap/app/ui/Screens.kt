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
import com.skorsnap.app.data.Mode
import com.skorsnap.app.data.Outcome
import com.skorsnap.app.data.Report
import com.skorsnap.app.data.Parlay
import com.skorsnap.app.data.Slip
import androidx.compose.runtime.saveable.rememberSaveable
import com.skorsnap.app.data.Comparison
import com.skorsnap.app.data.Lens
import com.skorsnap.app.data.Coach
import androidx.compose.ui.graphics.Color
import com.skorsnap.app.data.MarketOption
import kotlin.math.pow
import com.skorsnap.app.data.Strategy
import com.skorsnap.app.data.Leg
import kotlin.math.roundToInt
import com.skorsnap.app.data.SlipReport
import com.skorsnap.app.data.SavedSlip
import com.skorsnap.app.data.priceLabel
import com.skorsnap.app.data.twoDecimals
import com.skorsnap.app.data.Appetite
import com.skorsnap.app.data.Football

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
    onHistory: () -> Unit,
    onBrowse: () -> Unit,
    onSettings: () -> Unit,
) {
    // Played matches move to their own screen: the list is for deciding what to
    // bet, and a decided match is only clutter there.
    val pending = matches.filter { !it.settled }
    val done = matches.size - pending.size
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
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable(enabled = hasKey, onClick = onBrowse),
            ) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Cari pertandingan otomatis", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Ambil jadwal dan statistik sendiri — tanpa screenshot, jauh " +
                                "lebih hemat token.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("→", style = MaterialTheme.typography.titleMedium, color = Sky)
                }
            }
        }

        if (pending.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (done > 0) {
                            "Semua pertandingan sudah ada hasilnya.\n\n" +
                                "Yang sudah selesai pindah ke Riwayat. Tambah pertandingan " +
                                "baru dengan tombol di atas."
                        } else "Belum ada pertandingan.\n\n" +
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
            if (done > 0) item { HistoryLink(done, onHistory) }
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

        items(pending, key = { it.id }) { match ->
            MatchRow(match, match.id in selected, onOpen, onToggle)
        }

        if (done > 0) item { HistoryLink(done, onHistory) }

        item {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onReport) {
                Text(
                    if (done == 0) "Rapor akurasi" else "Rapor akurasi ($done hasil)",
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
private fun HistoryLink(count: Int, onHistory: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onHistory),
    ) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Riwayat", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "$count pertandingan yang sudah ada hasilnya",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("→", style = MaterialTheme.typography.titleMedium, color = Sky)
        }
    }
}

/**
 * Matches that have been played, kept out of the way but not thrown away: the
 * verdicts here are the record everything else is measured against.
 */
@Composable
fun HistoryScreen(matches: List<MatchPrediction>, onOpen: (String) -> Unit) {
    val done = remember(matches) { matches.filter { it.settled }.reversed() }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (done.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada pertandingan yang sudah ditandai hasilnya.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@LazyColumn
        }
        item {
            Text(
                "Buka salah satu kalau mau mengubah tandanya — hapus tandanya dan " +
                    "pertandingannya kembali ke daftar utama.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(done, key = { it.id }) { match ->
            MatchRow(match, checked = false, onOpen = onOpen, onToggle = {}, selectable = false)
        }
    }
}

@Composable
private fun MatchRow(
    match: MatchPrediction,
    checked: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    selectable: Boolean = true,
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
            if (selectable) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = { onToggle(match.id) },
                    colors = CheckboxDefaults.colors(checkedColor = Green),
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
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
                when (match.outcomeFor(Lens.BACKED)) {
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
    mode: Mode,
    onMode: (Mode) -> Unit,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onAnalyse: (String) -> Unit,
    /** What the previous pass said it still needed, when this is a second look. */
    wanted: List<String> = emptyList(),
    capturing: Boolean = false,
    captureProblem: String? = null,
    onStartCapture: () -> Unit = {},
    onStopCapture: () -> Unit = {},
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
        if (wanted.isNotEmpty()) {
            Card(
                title = "Data yang Diminta",
                subtitle = "Ini yang katanya bisa mengubah jawabannya. Kirim yang ketemu " +
                    "saja — kalau tidak ada, dia tetap harus memutuskan dengan yang ada.",
            ) {
                wanted.forEach {
                    Row(Modifier.padding(bottom = 5.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodySmall, color = Sky)
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (wanted.isEmpty()) Card(title = "Jenis Analisis") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Mode.entries.forEach { option ->
                    Surface(
                        color = if (mode == option) Sky.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).clickable { onMode(option) },
                    ) {
                        Text(
                            option.short,
                            modifier = Modifier.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (mode == option) FontWeight.Bold else FontWeight.Normal,
                            color = if (mode == option) Sky else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                when (mode) {
                    Mode.CORNER ->
                        "Khusus sepak pojok: total corner, corner babak 1, dan corner per tim. " +
                            "Kirim screenshot yang memuat statistik corner."
                    Mode.CORNER_1H ->
                        "Satu pasaran saja: total corner kedua tim di babak pertama, garis 4.5. " +
                            "Jawabannya cuma dua angka. Kirim screenshot yang memuat rata-rata " +
                            "corner babak 1 — kalau yang ada cuma angka satu laga penuh, " +
                            "keyakinannya akan diturunkan dan itu akan dikatakan apa adanya."
                    Mode.MATCH ->
                        "1X2, double chance, total gol, total babak 1, total per tim, " +
                            "kombinasi hasil + total, handicap Asia dan Eropa."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            title = "Tangkap Layar Langsung",
            subtitle = if (capturing) {
                "Tombol 📸 sudah aktif. Buka aplikasi statistikmu, tekan tombolnya di " +
                    "tiap halaman yang mau dibaca, lalu kembali ke sini."
            } else {
                "Tidak perlu screenshot manual. Nyalakan sekali, lalu tinggal tekan " +
                    "tombol melayang di aplikasi mana pun."
            },
        ) {
            Button(
                onClick = { if (capturing) onStopCapture() else onStartCapture() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (capturing) Rose else Green
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (capturing) "Matikan tombol tangkap" else "Nyalakan tangkap layar",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            captureProblem?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = Amber)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Android akan minta dua izin: tampil di atas aplikasi lain, dan rekam " +
                    "layar. Selama aktif ada notifikasi permanen — itu memang harus ada, " +
                    "supaya jelas kapan layarmu sedang bisa ditangkap. Gambarnya tetap di " +
                    "HP sampai kamu tekan Analisis.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

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
    onMark: (Lens, Outcome) -> Unit,
    onMarkMarket: (MarketOption, Outcome) -> Unit,
    onBacked: (String) -> Unit,
    onAddMore: () -> Unit,
    onDelete: () -> Unit,
    appetite: Appetite = Appetite.SAFE,
    prices: Map<String, Double> = emptyMap(),
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Card {
                Text(match.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOfNotNull(match.league.takeIf { it.isNotBlank() }, match.mode.label)
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

        if (match.verdict.isNotBlank()) {
            item { VerdictCard(match, onAddMore) }
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
                if (match.pickCorrected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Rekomendasi aslinya di luar rentang aman 68-92%, jadi diganti " +
                            "dengan peluang tertinggi yang masuk rentang.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Amber,
                    )
                }
            }
        }

        // The focused mode answers one question, so it shows one answer. The safe
        // shortlist, the 1X2 bars and a forty-row market table are all noise when
        // there are two markets and they sum to one.
        if (match.mode == Mode.CORNER_1H) {
            item { OneMarketCard(match, onMarkMarket) }
        } else {
            item { SafeListCard(match, appetite, onMarkMarket) }

            item {
                Card(title = "Hasil Akhir") {
                    Bar("${match.home} menang", match.probHome)
                    Bar("Seri", match.probDraw)
                    Bar("${match.away} menang", match.probAway)
                }
            }

            if (match.markets.isNotEmpty()) {
                item { MarketsCard(match) }
            }
        }

        item { OutcomeCard(match, onMark, onBacked) }

        if (match.risks.isNotEmpty() || match.adjustment.isNotBlank()) {
            item { ReasoningCard(match) }
        }

        if (prices.isNotEmpty()) {
            item {
                Card(
                    title = "Harga Bandar (${prices.size})",
                    subtitle = "Diambil bersama statistiknya. Ditampilkan apa adanya — " +
                        "nama market di sini beda dengan nama di aplikasi, jadi tidak " +
                        "kupasangkan otomatis supaya harga tidak nyangkut di taruhan yang salah.",
                ) {
                    prices.entries.take(24).forEach { (name, price) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(
                                name,
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                twoDecimals(price),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }

        item { StatsReadCard(match) }

        item {
            TextButton(onClick = onDelete) {
                Text("Hapus pertandingan ini", color = Rose, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/**
 * The safe-band markets, strongest first.
 *
 * One recommendation is still one recommendation — but a single row gave no way
 * to see what came a close second, and the alternatives were buried inside forty
 * grouped rows. This is the shortlist the pick is drawn from, in the order the
 * user asked for: highest first, and nothing outside the band that the badge
 * calls safe.
 */
@Composable
private fun SafeListCard(
    match: MatchPrediction,
    appetite: Appetite,
    onMark: (MarketOption, Outcome) -> Unit,
) {
    val safe = remember(match, appetite) { match.safePicks(appetite.floor) }
    if (safe.isEmpty()) {
        Card(title = "Tidak Ada yang Masuk Rentang") {
            Text(
                "Tidak satu pun market di laga ini jatuh di " +
                    "${Math.round(appetite.floor * 100)}-92%. Yang di bawah batas itu " +
                    "terlalu dekat lempar koin, yang di atas 92% odds-nya terlalu kecil " +
                    "untuk dipasang.\n\nHari seperti ini memang ada. Melewatkannya adalah " +
                    "keputusan yang sah — atau turunkan batasnya di Pengaturan kalau kamu " +
                    "memang mencari bayaran lebih besar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    Card(
        title = "Pilihan Aman (${safe.size})",
        subtitle = "Peluang tertinggi di atas. Semua di rentang " +
            "${Math.round(appetite.floor * 100)}-92%. " +
            "Tandai hasilnya setelah laga selesai — tiap tanda jadi bahan koreksi " +
            "buat analisis berikutnya.",
    ) {
        safe.forEachIndexed { index, option ->
            val isPick = option.name == match.pick
            Row(
                Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${index + 1}",
                    Modifier.width(22.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            option.name,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (isPick) FontWeight.Bold else FontWeight.Normal,
                        )
                        if (isPick) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = Green.copy(alpha = 0.20f), shape = RoundedCornerShape(5.dp)) {
                                Text(
                                    "rekomendasi",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Green,
                                )
                            }
                        }
                    }
                    Text(
                        if (option.derived) "${option.group} · dihitung" else option.group,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${option.percent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = probColor(option.prob),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "%.2f".format(option.breakEven),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Marking each safe option, not just the one bet, is what turns a single
            // match into several observations — the record the model is fed grows in
            // weeks rather than months.
            val verdict = match.outcomeOf(option)
            Row(
                Modifier.fillMaxWidth().padding(start = 22.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Verdict("Tembus", verdict == Outcome.WON, Green, Modifier.weight(1f)) {
                    onMark(option, Outcome.WON)
                }
                Verdict("Meleset", verdict == Outcome.LOST, Rose, Modifier.weight(1f)) {
                    onMark(option, Outcome.LOST)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Peluang tertinggi bukan jaminan tertinggi — rapormu sendiri menunjukkan " +
                "satu market 85% yang sering meleset. Angka kanan tetap odds impasnya.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (safe.any { it.derived }) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Bertanda \"dihitung\" artinya angkanya diturunkan dari perkiraan gol, " +
                    "bukan disebut langsung oleh model. Rekomendasi selalu mendahulukan " +
                    "market yang model sendiri nilai.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every market the analysis produced, grouped so forty rows stay readable.
 *
 * Each row carries the break-even price beside the probability, because that pair
 * is the whole of value betting: back it when the bookmaker pays more than the
 * break-even figure, leave it when they pay less, however comfortable the
 * percentage looks on its own. The app cannot see the bookmaker's prices, so it
 * gives the number to compare them against rather than pretending to judge.
 */
@Composable
private fun MarketsCard(match: MatchPrediction) {
    var bySafety by remember { mutableStateOf(true) }
    val groups = remember(match, bySafety) {
        if (bySafety) match.grouped()
        else listOf("Bayaran terbesar dulu" to match.markets.sortedBy { it.prob })
    }

    Card(
        title = "Semua Market (${match.markets.size})",
        subtitle = "Angka kanan adalah odds impas — pasang kalau bandar bayar di atas itu.",
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortChip("Paling aman", bySafety, Modifier.weight(1f)) { bySafety = true }
            SortChip("Bayaran terbesar", !bySafety, Modifier.weight(1f)) { bySafety = false }
        }
        Spacer(Modifier.height(4.dp))
        groups.forEach { (group, options) ->
            MarketGroup(group, options, expandedByDefault = groups.size == 1 || options.any { it.safe })
        }
    }
}

@Composable
private fun SortChip(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        color = if (active) Sky.copy(alpha = 0.20f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) Sky else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MarketGroup(
    group: String,
    options: List<com.skorsnap.app.data.MarketOption>,
    expandedByDefault: Boolean,
) {
    var open by remember(group) { mutableStateOf(expandedByDefault) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                group,
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = Sky,
            )
            Text(
                "${options.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(if (open) "−" else "+", style = MaterialTheme.typography.titleMedium)
        }
        AnimatedVisibility(open) {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(option.name, style = MaterialTheme.typography.bodySmall)
                                if (option.safe) {
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        color = Green.copy(alpha = 0.18f),
                                        shape = RoundedCornerShape(5.dp),
                                    ) {
                                        Text(
                                            "aman",
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Green,
                                        )
                                    }
                                }
                            }
                            if (option.why.isNotBlank()) {
                                Text(
                                    option.why,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            "${option.percent}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = probColor(option.prob),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "%.2f".format(option.breakEven),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
private fun OutcomeCard(
    match: MatchPrediction,
    onMark: (Lens, Outcome) -> Unit,
    onBacked: (String) -> Unit,
) {
    var choosing by remember(match.id) { mutableStateOf(false) }
    Card(
        title = "Sudah Main?",
        subtitle = "Catat dua-duanya: rekomendasi aplikasi dan market yang kamu pasang. " +
            "Itu satu-satunya cara tahu mana yang lebih baik.",
    ) {
        // The recommendation and the bet are often different markets. Recording
        // the wrong one is what made the report unanswerable.
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().clickable { choosing = !choosing },
        ) {
            Row(Modifier.padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Yang kamu pasang",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        match.backedMarket.ifBlank { "belum dipilih" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    "${Math.round(match.probFor(Lens.BACKED) * 100)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = probColor(match.probFor(Lens.BACKED)),
                )
                Spacer(Modifier.width(10.dp))
                Text(if (choosing) "−" else "ganti", style = MaterialTheme.typography.labelSmall, color = Sky)
            }
        }
        AnimatedVisibility(choosing) {
            Column(Modifier.padding(top = 8.dp)) {
                match.markets.sortedByDescending { it.prob }.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onBacked(option.name)
                                choosing = false
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            option.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (option.name == match.backedMarket) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            "${option.percent}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = probColor(option.prob),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // Two separate records. With one shared flag, backing anything other than
        // the recommendation left the app's own advice unmeasured — so the question
        // the user kept asking, whether its pick beats their own, had no answer.
        OutcomeRow(
            label = "Rekomendasi aplikasi",
            market = match.pick,
            percent = Math.round(match.pickProb * 100).toInt(),
            current = match.pickOutcome,
        ) { onMark(Lens.PICK, it) }

        if (match.divergent) {
            Spacer(Modifier.height(12.dp))
            OutcomeRow(
                label = "Market yang kamu pasang",
                market = match.backedMarket,
                percent = Math.round(match.probFor(Lens.BACKED) * 100).toInt(),
                current = match.backedOutcome,
            ) { onMark(Lens.BACKED, it) }
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "Kamu memasang market yang sama dengan rekomendasi, jadi satu catatan " +
                    "sudah cukup. Ganti market di atas kalau kamu pasang yang lain.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * Over against Under, side by side, with the verdict buttons on each.
 *
 * Two numbers that sum to one do not need a ranked shortlist wrapped around them.
 * The break-even price stays, because it is the only number that decides whether
 * either side is worth backing at the price on offer.
 */
@Composable
private fun OneMarketCard(match: MatchPrediction, onMark: (MarketOption, Outcome) -> Unit) {
    val pair = remember(match) {
        listOf("Corner babak 1 Over 4.5", "Corner babak 1 Under 4.5")
            .mapNotNull { name -> match.markets.firstOrNull { it.name == name } }
    }
    if (pair.size < 2) {
        Card(title = "Jawaban Tidak Lengkap") {
            Text(
                "Model tidak mengembalikan kedua sisi pasaran ini. Coba analisis ulang, " +
                    "atau kirim screenshot yang memuat rata-rata corner babak 1.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
        return
    }

    Card(
        title = "Corner Babak 1 — Garis 4.5",
        subtitle = "Total corner kedua tim di babak pertama saja.",
    ) {
        pair.forEach { option ->
            val leading = option.name == match.pick
            Surface(
                color = if (leading) Green.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (option.name.contains("Over")) "Over 4.5" else "Under 4.5",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (leading) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(
                                if (option.name.contains("Over")) "5 corner atau lebih"
                                else "4 corner atau kurang",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "${option.percent}%",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = probColor(option.prob),
                            )
                            Text(
                                "pasang di atas %s".format(twoDecimals(option.breakEven)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val verdict = match.outcomeOf(option)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Verdict("Tembus", verdict == Outcome.WON, Green, Modifier.weight(1f)) {
                            onMark(option, Outcome.WON)
                        }
                        Verdict("Meleset", verdict == Outcome.LOST, Rose, Modifier.weight(1f)) {
                            onMark(option, Outcome.LOST)
                        }
                    }
                }
            }
        }
        // firstOrNull, not first: the pick can be blank, or a name outside this
        // pair, and a reason line is not worth crashing the screen over.
        Text(
            (pair.firstOrNull { it.name == match.pick } ?: pair.first()).why,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The answer, first and in one sentence.
 *
 * Everything below this card is the argument; this is the conclusion, and it was
 * missing. A page that ends on considerations leaves the reader to draw their own
 * verdict from a screen full of caveats, which is exactly the state this app was
 * supposed to save them from.
 */
@Composable
private fun VerdictCard(match: MatchPrediction, onAddMore: () -> Unit) {
    val colour = when {
        match.wantsMore -> Sky
        match.standDown -> Amber
        else -> Green
    }
    val heading = when {
        match.wantsMore -> "Butuh Data Dulu"
        match.standDown -> "Lewatkan Laga Ini"
        else -> "Kesimpulan"
    }
    Surface(
        color = colour.copy(alpha = 0.12f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(15.dp)) {
            Text(heading, style = MaterialTheme.typography.labelSmall, color = colour)
            Spacer(Modifier.height(6.dp))
            Text(
                match.verdict,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (match.needMore.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Yang dia butuhkan:",
                    style = MaterialTheme.typography.labelSmall,
                    color = colour,
                )
                Spacer(Modifier.height(4.dp))
                match.needMore.forEach {
                    Row(Modifier.padding(bottom = 4.dp)) {
                        Text("• ", style = MaterialTheme.typography.bodySmall, color = colour)
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onAddMore,
                colors = ButtonDefaults.buttonColors(containerColor = colour),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (match.needMore.isEmpty()) "Tambah data & analisis ulang"
                    else "Kirim data ini & analisis ulang",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (match.needMore.isEmpty() && !match.wantsMore) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Datanya sudah dianggap cukup — tombol di atas cuma kalau kamu " +
                        "punya statistik tambahan yang mau dicoba.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The reasoning chain: raw read, doubts, which way they push, final number.
 *
 * Laid out as steps rather than prose because the failure worth catching is a
 * missing step — doubts written down and then ignored, the final number identical
 * to the first read. Side by side that is obvious in a glance; buried in a
 * paragraph it is not, which is how a 72% Over survived two reasons to go Under.
 */
@Composable
private fun ReasoningCard(match: MatchPrediction) {
    Card(
        title = "Cara Dia Sampai ke Angka Itu",
        subtitle = "Baca dari atas ke bawah. Kalau angka akhirnya sama dengan kesan " +
            "awal padahal ada dua keraguan, berarti keraguannya tidak dipakai.",
    ) {
        if (match.firstRead.isNotBlank()) {
            Step("1. Kata statistiknya saja", match.firstRead, Sky)
            Spacer(Modifier.height(10.dp))
        }
        if (match.risks.isNotEmpty()) {
            Text(
                "2. Yang bikin ragu",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
            )
            Spacer(Modifier.height(4.dp))
            match.risks.forEach { risk ->
                Row(Modifier.padding(bottom = 4.dp)) {
                    Text("• ", style = MaterialTheme.typography.bodySmall, color = Amber)
                    Text(
                        risk,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (match.riskSide.isNotBlank()) {
                Text(
                    "Keduanya mendorong ke: ${match.riskSide}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (match.adjustment.isNotBlank()) {
            Step("3. Angka setelah digeser", match.adjustment, Green)
        }
        if (match.confidenceWhy.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Yang paling mungkin mengubah jawaban ini: ${match.confidenceWhy}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Step(label: String, body: String, colour: Color) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = colour)
    Spacer(Modifier.height(4.dp))
    Text(
        body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Picker button plus the thumbnails, shared by the two screens that stage images. */
@Composable
private fun ImageStrip(staged: List<ByteArray>, onPick: () -> Unit, onRemove: (Int) -> Unit) {
    Button(
        onClick = onPick,
        colors = ButtonDefaults.buttonColors(containerColor = Sky),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (staged.isEmpty()) "Tambah screenshot (opsional)" else "Tambah gambar lagi",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (staged.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        staged.forEachIndexed { index, bytes ->
            Box {
                val bitmap = remember(bytes) { Images.preview(bytes)?.asImageBitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(72.dp, 112.dp).clip(RoundedCornerShape(10.dp)),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Box(
                        Modifier.size(72.dp, 112.dp).clip(RoundedCornerShape(10.dp))
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
}

/**
 * Fixtures for a date, so a match can be chosen instead of photographed.
 *
 * Choosing here fetches both teams' season statistics as text. That is roughly a
 * thousand tokens where the same numbers as a screenshot are thirty thousand, and
 * a number read from a field cannot be misread the way one read from an image can.
 *
 * Screenshots stay available on the same screen, because the free feed carries no
 * corner statistics at all — the one thing this user's strategy runs on.
 */
@Composable
fun BrowseScreen(
    fixtures: List<Football.Fixture>,
    busy: Boolean,
    report: String?,
    staged: List<ByteArray>,
    hasKey: Boolean,
    onLoad: (String) -> Unit,
    onPick: () -> Unit,
    onRemove: (Int) -> Unit,
    onAnalyse: (Football.Fixture, String) -> Unit,
    onSettings: () -> Unit,
) {
    var chosen by remember { mutableStateOf<Football.Fixture?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    val dates = remember {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val cal = java.util.Calendar.getInstance()
        (0..3).map {
            val d = fmt.format(cal.time)
            cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
            d
        }
    }
    var date by rememberSaveable { mutableStateOf(dates.first()) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (!hasKey) {
            item {
                Card(title = "Pasang Kunci API-Football") {
                    Text(
                        "Daftar gratis di dashboard.api-football.com — 100 permintaan per " +
                            "hari, cukup untuk sekitar 25 laga. Tempel kuncinya di " +
                            "Pengaturan, lalu kembali ke sini.",
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
            return@LazyColumn
        }

        item {
            Card(title = "Tanggal") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    dates.forEach { d ->
                        val on = d == date
                        Surface(
                            color = if (on) Sky.copy(alpha = 0.20f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).clickable {
                                date = d
                                onLoad(d)
                            },
                        ) {
                            Text(
                                d.takeLast(5),
                                modifier = Modifier.padding(vertical = 10.dp),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                color = if (on) Sky else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onLoad(date) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (busy) "Mengambil…" else "Muat pertandingan",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (report != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        report,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (report.startsWith("Gagal")) Rose else Green,
                    )
                }
            }
        }

        if (fixtures.isEmpty()) return@LazyColumn

        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Cari tim atau liga") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
        }

        val shown = fixtures.filter {
            query.isBlank() || "${it.title} ${it.where}".contains(query, ignoreCase = true)
        }

        item {
            Text(
                "${shown.size} pertandingan. Pilih satu, lalu tekan Analisis.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(shown, key = { it.id }) { fx ->
            val on = chosen?.id == fx.id
            Surface(
                color = if (on) Sky.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().clickable { chosen = if (on) null else fx },
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            fx.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            fx.where,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        fx.kickoff.takeLast(5),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        chosen?.let { fx ->
            item {
                Card(
                    title = "Analisis ${fx.title}",
                    subtitle = "Harga bandar diambil otomatis dan dipakai sebagai " +
                        "titik awal. Tambahkan screenshot untuk statistik corner — " +
                        "sumber ini tidak punya itu.",
                ) {
                    ImageStrip(staged, onPick, onRemove)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Catatan tambahan (opsional)") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { onAnalyse(fx, note) },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = Green),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (busy) "Menganalisis…" else "Analisis pertandingan ini",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Memakai 1-3 permintaan dari jatah harianmu. Di paket gratis " +
                            "yang terambil adalah harga bandar; statistik musim hanya " +
                            "terbuka di paket berbayar.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A compact tembus/meleset chip for a row inside a list. */
@Composable
private fun Verdict(
    text: String,
    active: Boolean,
    colour: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) colour.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Text(
            text,
            modifier = Modifier.padding(vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            color = if (active) colour else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** One label, the market it refers to, and the two verdicts. */
@Composable
private fun OutcomeRow(
    label: String,
    market: String,
    percent: Int,
    current: Outcome,
    onMark: (Outcome) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = Sky)
    Text(
        "${market.ifBlank { "belum ada" }} · $percent%",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutcomeButton(
            text = "Tembus",
            active = current == Outcome.WON,
            colour = Green,
            modifier = Modifier.weight(1f),
        ) { onMark(Outcome.WON) }
        OutcomeButton(
            text = "Meleset",
            active = current == Outcome.LOST,
            colour = Rose,
            modifier = Modifier.weight(1f),
        ) { onMark(Outcome.LOST) }
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
fun SlipScreen(
    matches: List<MatchPrediction>,
    strategy: Strategy,
    onStrategy: (Strategy) -> Unit,
    chosen: Map<String, String>,
    onChoose: (String, String) -> Unit,
    onBest: () -> Unit,
    odds: Map<String, Double>,
    onOdds: (String, Double) -> Unit,
    onSave: (Slip, Double) -> Unit,
    onOpen: (String) -> Unit,
    onClear: () -> Unit,
) {
    val slip = remember(matches, strategy, odds, chosen) {
        val built = Parlay.build(matches, strategy, chosen)
        built.copy(legs = built.legs.map { it.copy(odds = odds["${it.matchId}|${it.market}"] ?: 0.0) })
    }
    val skipped = remember(matches, strategy, chosen) { Parlay.skipped(matches, strategy, chosen) }
    var stake by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (matches.isEmpty()) {
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
            Card(
                title = "Buatkan Parlay",
                subtitle = "Pilih cara mengambil satu market dari tiap laga.",
            ) {
                Strategy.entries.forEach { option ->
                    val on = strategy == option
                    Surface(
                        color = if (on) Sky.copy(alpha = 0.18f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .clickable { onStrategy(option) },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) Sky else MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                option.note,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (skipped.isNotEmpty()) {
            item {
                Surface(
                    color = Amber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${skipped.size} laga tidak dipakai karena tidak punya market di " +
                            "rentang aman: ${skipped.joinToString { it.title }}. Menambal slip " +
                            "dengan lemparan koin cuma bikin parlaynya tidak bisa menang.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
            }
        }

        if (slip.size == 0) return@LazyColumn

        item {
            Card(title = "Parlay ${slip.size} Leg") {
                Row {
                    Stat("Tembus semua", "${slip.percent}%", Modifier.weight(1f))
                    Stat("Kira-kira", "1 dari ${slip.oneInN}", Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row {
                    Stat("Bayaran wajar", "%.2f".format(slip.fairOdds), Modifier.weight(1f))
                    Stat(
                        if (slip.priced) "Bayaran Melbet" else "Perkiraan bandar",
                        "%.2f".format(if (slip.priced) slip.bookOdds else slip.fairOdds / Parlay.MARGIN.pow(slip.size)),
                        Modifier.weight(1f),
                    )
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

        item { ValueCard(slip, onBest) }

        if (slip.weakLegs.isNotEmpty()) {
            item {
                Surface(
                    color = Amber.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "${slip.weakLegs.size} leg datanya tipis: " +
                            slip.weakLegs.joinToString { "${it.home} vs ${it.away}" } +
                            ". Screenshot yang lebih lengkap akan memperbaikinya.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Amber,
                    )
                }
            }
        }

        items(slip.legs, key = { it.matchId }) { leg ->
            // Deleting a match while this screen is open would otherwise crash here.
            val match = matches.firstOrNull { it.id == leg.matchId } ?: return@items
            LegRow(
                leg = leg,
                match = match,
                odds = odds,
                onOpen = { onOpen(leg.matchId) },
                onOdds = onOdds,
                onChoose = { onChoose(leg.matchId, it) },
            )
        }

        item {
            Card(
                title = "Simpan Slip Ini",
                subtitle = "Supaya parlaynya bisa dinilai sebagai parlay, bukan cuma per leg.",
            ) {
                OutlinedTextField(
                    value = stake,
                    onValueChange = { raw -> stake = raw.filter { it.isDigit() } },
                    label = { Text("Nominal pasang (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onSave(slip, stake.toDoubleOrNull() ?: 0.0) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Simpan slip", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Isi nominalnya kalau mau rapor untung-rugi dalam rupiah. Kosongkan " +
                        "kalau cuma mau menghitung berapa sering parlaymu tembus.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
 * One leg, with a box for the price the bookmaker is actually offering.
 *
 * Typed by hand rather than read from a screenshot: three or four numbers is a few
 * seconds of typing, while sending a bookmaker screenshot to the model costs
 * thousands of tokens per slip and can misread a digit. The number matters too much
 * to guess at.
 */
@Composable
private fun LegRow(
    leg: Leg,
    match: MatchPrediction,
    odds: Map<String, Double>,
    onOpen: () -> Unit,
    onOdds: (String, Double) -> Unit,
    onChoose: (String) -> Unit,
) {
    var swapping by remember(leg.matchId) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${leg.home} vs ${leg.away}", style = MaterialTheme.typography.bodyMedium)
                    Text(leg.market, style = MaterialTheme.typography.labelSmall, color = Sky)
                }
                Text(
                    "${leg.percent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = probColor(leg.prob),
                )
            }
            Spacer(Modifier.height(8.dp))
            PriceRow(leg, odds) { onOdds("${leg.matchId}|${leg.market}", it) }

            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { swapping = !swapping }) {
                Text(
                    if (swapping) "Tutup" else "Ganti market",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            if (swapping) {
                Text(
                    "Isi odds Melbet untuk beberapa market di bawah, lalu pilih salah satu " +
                        "— atau pakai tombol berbayaran terbaik di atas.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                // The safe band only: swapping a bad price for a coin flip is not an
                // improvement, however generous the price looks.
                match.safePicks().forEach { option ->
                    val key = "${match.id}|${option.name}"
                    val price = odds[key] ?: 0.0
                    val current = option.name == leg.market
                    Surface(
                        color = if (current) Sky.copy(alpha = 0.14f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 5.dp),
                    ) {
                        Row(
                            Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                Modifier.weight(1f).clickable { onChoose(option.name) }
                            ) {
                                Text(
                                    option.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                                )
                                val edge = if (price > 1.0) price * option.prob - 1.0 else 0.0
                                Text(
                                    priceLabel(option, price),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        price <= 1.0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                        edge > 0 -> Green
                                        else -> Rose
                                    },
                                )
                            }
                            OddsBox(key, price) { onOdds(key, it) }
                        }
                    }
                }
            }
        }
    }
}

/** Break-even price, the verdict once a real price is in, and the input itself. */
@Composable
private fun PriceRow(leg: Leg, odds: Map<String, Double>, onOdds: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Minimal %.2f".format(leg.breakEven),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (leg.priced) {
                Text(
                    if (leg.edge > 0) {
                        "Melbet bayar lebih tinggi — untung %+d%%".format(leg.edgePercent)
                    } else {
                        "Melbet bayar di bawah minimal — rugi %d%%".format(leg.edgePercent)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (leg.edge > 0) Green else Rose,
                )
            }
        }
        OddsBox("${leg.matchId}|${leg.market}", odds["${leg.matchId}|${leg.market}"] ?: 0.0, onOdds)
    }
}

@Composable
private fun OddsBox(key: String, value: Double, onOdds: (Double) -> Unit) {
    var text by remember(key) { mutableStateOf(if (value > 1.0) "%.2f".format(value) else "") }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw.replace(',', '.').filter { it.isDigit() || it == '.' }
            onOdds(text.toDoubleOrNull() ?: 0.0)
        },
        label = { Text("Odds", style = MaterialTheme.typography.labelSmall) },
        singleLine = true,
        modifier = Modifier.width(104.dp),
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

/**
 * Why the number beside a market is not the bookmaker's price, and what to do
 * about it.
 *
 * The two were being read as the same thing and they are opposites: one is the
 * least the bet may pay to be worth taking, the other is what is on offer. Setting
 * them side by side is the entire value decision, so the screen states it outright
 * rather than leaving it to be inferred.
 */
@Composable
private fun ValueCard(slip: Slip, onBest: () -> Unit) {
    Card(
        title = "Nilai Sebenarnya",
        subtitle = "Angka di aplikasi bukan harga bandar — itu harga minimalnya.",
    ) {
        Text(
            "Angka 1,18 di sebelah market artinya: taruhan ini baru layak kalau bandar " +
                "membayar di atas 1,18. Melbet menampilkan harga yang mereka tawarkan. " +
                "Dua angka yang berbeda, jadi memang tidak akan pernah sama — dan justru " +
                "membandingkan keduanya itulah cara menemukan taruhan yang menguntungkan.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!slip.priced) {
            Text(
                "Isi kolom Odds di tiap leg dengan harga dari Melbet, nanti dihitungkan " +
                    "apakah parlay ini menguntungkan atau tidak. Diketik saja — kirim " +
                    "screenshot odds ke model itu boros token dan bisa salah baca angka.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
            return@Card
        }

        Row {
            Stat("Bayaran Melbet", "%.2f".format(slip.bookOdds), Modifier.weight(1f))
            Stat("Harga wajar", "%.2f".format(slip.fairOdds), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Surface(
            color = (if (slip.worthTaking) Green else Rose).copy(alpha = 0.12f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (slip.worthTaking) {
                    "Menguntungkan menurut angka aplikasi: tiap Rp 100.000 dipasang " +
                        "diperkirakan kembali Rp %,.0f. Ini bergantung sepenuhnya pada " +
                        "peluang di atas benar — dan itu yang sedang diuji rapormu."
                            .format(slip.expectedReturn * 100000)
                } else {
                    "Merugikan menurut angka aplikasi: tiap Rp 100.000 dipasang " +
                        "diperkirakan kembali Rp %,.0f. Cari harga yang lebih tinggi, " +
                        "kurangi leg, atau lewatkan."
                            .format(slip.expectedReturn * 100000)
                },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (slip.worthTaking) Green else Rose,
            )
        }
        if (slip.badlyPriced.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Leg yang harganya di bawah minimal: " +
                    slip.badlyPriced.joinToString { it.market } +
                    ". Tekan \"Ganti market\" di leg itu, isi odds beberapa market lain " +
                    "dari Melbet, lalu pakai tombol di bawah.",
                style = MaterialTheme.typography.labelSmall,
                color = Rose,
            )
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onBest, modifier = Modifier.fillMaxWidth()) {
            Text("Pakai market berbayaran terbaik", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Menukar tiap leg ke market yang bayarannya paling jauh di atas harga " +
                "minimal — hanya di antara market yang sudah kamu isi odds-nya. Tanpa " +
                "odds, tidak ada yang bisa dibandingkan.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One saved slip, with its verdict and a way to drop a mis-saved one. */
@Composable
private fun SlipRow(saved: SavedSlip, onMark: (Outcome) -> Unit, onRemove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${saved.size} leg · ${saved.strategy}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        saved.legs.joinToString(" + ") { "${it.home} ${it.market}" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${saved.percent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = probColor(saved.combined),
                )
            }
            if (saved.priced) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (saved.stake > 0) {
                        "Bayaran %.2f · pasang Rp %,.0f".format(saved.bookOdds, saved.stake)
                    } else {
                        "Bayaran %.2f".format(saved.bookOdds)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Verdict("Tembus", saved.outcome == Outcome.WON, Green, Modifier.weight(1f)) {
                    onMark(Outcome.WON)
                }
                Verdict("Meleset", saved.outcome == Outcome.LOST, Rose, Modifier.weight(1f)) {
                    onMark(Outcome.LOST)
                }
                TextButton(onClick = onRemove) {
                    Text("Hapus", style = MaterialTheme.typography.labelSmall, color = Rose)
                }
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
    val usage by vm.lastUsage.collectAsStateWithLifecycle()
    val appetite by vm.appetite.collectAsStateWithLifecycle()
    val fixturesBusy by vm.fixturesBusy.collectAsStateWithLifecycle()
    val footballReport by vm.footballReport.collectAsStateWithLifecycle()
    var model by remember(available) { mutableStateOf(vm.store.model) }

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Card(title = "1. Kunci Gemini", subtitle = "Dibutuhkan untuk membaca gambar.") {
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
                onValueChange = { key = it.trim() },
                label = { Text("AQ.Ab8... atau AIza...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.saveAndCheckKey(key) },
                enabled = !modelsBusy && key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (modelsBusy) "Memeriksa…" else "Simpan & Cek Kunci")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (vm.store.hasKey) {
                    "Ada kunci tersimpan. Tekan tombol di atas kalau kamu baru menggantinya."
                } else {
                    "Belum ada kunci tersimpan. Tempel kuncinya lalu tekan tombol di atas."
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (vm.store.hasKey) MaterialTheme.colorScheme.onSurfaceVariant else Amber,
            )
        }

        Card(
            title = "2. Model",
            subtitle = "Daftarnya diambil langsung dari Google, bukan ditebak. " +
                "Pilih satu, lalu tes.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.loadModels() },
                    enabled = !modelsBusy && vm.store.hasKey,
                    colors = ButtonDefaults.buttonColors(containerColor = Sky),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (modelsBusy) "…" else "Muat daftar", style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { vm.testModel() },
                    enabled = !modelsBusy && vm.store.hasKey,
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

            usage?.let {
                Text(
                    "Analisis terakhir memakai ${it.total} token " +
                        "(${it.input} baca gambar, ${it.thinking} berpikir, ${it.output} jawaban).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                "Kalau kuncimu sudah pakai penagihan, tidak ada lagi jatah gratis — " +
                    "tiap panggilan dihitung. Screenshot panjang itu bagian yang paling " +
                    "mahal karena dibaca sebagai puluhan ribu token. Model Pro beberapa " +
                    "kali lipat harga Flash untuk gambar yang sama; Flash Lite paling murah.",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
            )
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

        Card(
            title = "3. Kunci API-Football (opsional)",
            subtitle = "Untuk mengambil jadwal dan statistik sendiri. Gratis 100 " +
                "permintaan/hari di dashboard.api-football.com.",
        ) {
            var key by rememberSaveable { mutableStateOf(vm.store.footballKey) }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("Kunci API-Football") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { vm.saveAndCheckFootballKey(key) },
                enabled = !fixturesBusy,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (fixturesBusy) "Mengecek…" else "Simpan & Cek Kunci",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            footballReport?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it.startsWith("Gagal")) Rose else Green,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Sudah kuuji dengan kunci gratis sungguhan: yang terbuka adalah jadwal " +
                    "hari ini dan harga bandar. Statistik musim, tanggal lampau, dan " +
                    "riwayat laga sebuah tim TERKUNCI di paket gratis — jadi rata-rata " +
                    "corner tidak bisa dihitung otomatis. Screenshot FootyStats-mu tetap " +
                    "diperlukan untuk itu.",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
            )
        }

        Card(
            title = "4. Selera Risiko",
            subtitle = "Seberapa rendah peluang yang boleh direkomendasikan. " +
                "Ini tidak mengubah angkanya — cuma market mana yang dipilih.",
        ) {
            Appetite.entries.forEach { option ->
                val on = appetite == option
                Surface(
                    color = if (on) Sky.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .clickable { vm.setAppetite(option) },
                ) {
                    Column(Modifier.padding(11.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                color = if (on) Sky else MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "batas ${Math.round(option.floor * 100)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            option.note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Menurunkan batas berarti lebih sering meleset — itu memang harga dari " +
                    "bayaran yang lebih besar, bukan tanda prediksinya memburuk. " +
                    "Rapor akurasimu yang akan menjawab apakah pilihan ini menguntungkan.",
                style = MaterialTheme.typography.labelSmall,
                color = Amber,
            )
        }

        Card(title = "Kalau Semua Model Gagal") {
            Text(
                "Tekan \"Tes model ini\" dan baca baris \"Kata Google\" — pesan aslinya " +
                    "ada di situ, bukan tebakan saya. Tiga penyebab yang paling sering:\n\n" +
                    "• \"Kuota habis\" — jatah gratis harian sudah terpakai. Tunggu sampai " +
                    "besok, atau pakai model Flash yang jatahnya lebih besar.\n\n" +
                    "• \"Kunci ditolak\" — kuncinya sudah dihapus atau salah salin. Buat " +
                    "yang baru di aistudio.google.com, lalu Simpan & Cek Kunci di atas.\n\n" +
                    "• \"Model tidak ada\" — model itu memang tidak dibuka untuk kuncimu. " +
                    "Pilih yang lain, biasanya yang namanya paling sederhana.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
fun ReportScreen(
    matches: List<MatchPrediction>,
    slips: List<SavedSlip>,
    onMarkSlip: (String, Outcome) -> Unit,
    onRemoveSlip: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val slipReport = remember(slips) { SlipReport(slips) }
    var lens by rememberSaveable { mutableStateOf(Lens.BACKED) }
    val report = remember(matches, lens) { Report(matches, lens) }
    val comparison = remember(matches) { Comparison(matches) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            // Two records, two answers. Which one is on screen has to be explicit,
            // or the reader cannot tell whose accuracy the number describes.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lens.entries.forEach { option ->
                    val on = lens == option
                    Surface(
                        color = if (on) Sky.copy(alpha = 0.20f)
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).clickable { lens = option },
                    ) {
                        Text(
                            option.label,
                            modifier = Modifier.padding(vertical = 9.dp, horizontal = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = if (on) Sky else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        if (report.total == 0 && report.allMarks().isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().height(280.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Belum ada hasil untuk ${lens.label.lowercase()}.\n\n" +
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

        // Guarded: the safe-list marks can exist before either role on a match has
        // been settled, and a hit rate of 0/0 reads as a catastrophe rather than as
        // an empty cell.
        if (report.total == 0) {
            item {
                Card(title = "Belum Ada Hasil di Sudut Ini") {
                    Text(
                        "Kamu sudah menandai market di daftar aman, tapi belum menandai " +
                            "${lens.label.lowercase()}. Tandai di halaman laganya supaya " +
                            "perbandingan di bawah ada isinya.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (report.total > 0) item {
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

        if (report.total > 0) item {
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

        if (report.total > 0) item {
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
            Card(
                title = "Rekomendasi vs Pilihanmu",
                subtitle = "Hanya laga yang dua-duanya tercatat dan pilihannya berbeda.",
            ) {
                if (comparison.n > 0) {
                    Row {
                        Stat(
                            "Rekomendasi",
                            "${comparison.pickWon}/${comparison.n}",
                            Modifier.weight(1f),
                        )
                        Stat(
                            "Pilihanmu",
                            "${comparison.backedWon}/${comparison.n}",
                            Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    comparison.verdict,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (slips.isNotEmpty()) {
            item {
                Card(
                    title = "Rapor Parlay (${slips.size} slip)",
                    subtitle = "Parlay dinilai sebagai parlay — catatan per market tidak bisa " +
                        "bilang berapa slip yang mati di satu leg saja.",
                ) {
                    if (slipReport.total > 0) {
                        Row {
                            Stat(
                                "Tembus",
                                "${slipReport.won}/${slipReport.total}",
                                Modifier.weight(1f),
                            )
                            Stat(
                                "Dijanjikan",
                                "${Math.round(slipReport.promised * 100)}%",
                                Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(
                        slipReport.verdict,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (slipReport.hasMoney && slipReport.profit < 0) Rose
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val byLegs = slipReport.byLegCount()
            if (byLegs.size > 1) {
                item {
                    SliceCard(
                        "Per Jumlah Leg",
                        "Di sinilah kerugian parlay biasanya muncul.",
                        byLegs,
                    )
                }
            }

            items(slips.reversed(), key = { it.id }) { saved ->
                SlipRow(saved, onMark = { onMarkSlip(saved.id, it) }, onRemove = { onRemoveSlip(saved.id) })
            }
        }

        val marked = report.byMarkedMarket()
        if (marked.isNotEmpty()) {
            item {
                SliceCard(
                    "Semua Market yang Kamu Tandai (${report.allMarks().size} tanda)",
                    "Termasuk pilihan aman yang kamu tandai tapi tidak kamu pasang. " +
                        "Ini bahan koreksi terbanyak — tiap tanda satu janji yang ditepati " +
                        "atau tidak.",
                    marked,
                )
            }
        }

        item {
            Card(
                title = "Yang Dipelajari Model",
                subtitle = "Catatan ini ikut dikirim setiap kali kamu menganalisis laga baru.",
            ) {
                Text(
                    remember(matches, slips) { Coach.summary(matches, slips) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Ini bukan model yang dilatih ulang — modelnya tetap punya Google dan " +
                        "tidak berubah. Yang berubah cuma apa yang diberitahukan ke dia " +
                        "sebelum menganalisis. Efeknya nyata tapi terbatas, dan makin " +
                        "banyak hasil yang kamu tandai, makin berguna catatannya.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Amber,
                )
            }
        }

        val byGroup = report.byGroup()
        if (byGroup.size > 1) {
            item { SliceCard("Per Kelompok Market", "Di mana akurasinya benar-benar berada.", byGroup) }
        }
        val byMarket = report.byMarket()
        if (byMarket.isNotEmpty()) {
            item {
                SliceCard(
                    "Per Market Persis",
                    "Over 1.5 dan Under 3.5 sama-sama \"Total Gol\" tapi taruhan yang berbeda. " +
                        "Yang muncul di sini minimal dua kali tercatat.",
                    byMarket,
                )
            }
        }
        val byModel = report.byModel()
        if (byModel.size > 1) {
            item {
                SliceCard(
                    "Per Model Gemini",
                    "Kalau satu model jelas lebih baik, ini yang akan menunjukkannya.",
                    byModel,
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
                        if (match.outcomeFor(lens) == Outcome.WON) "tembus ✓" else "meleset ✗",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (match.outcomeFor(lens) == Outcome.WON) Green else Rose,
                    )
                }
            }
        }
    }
}


/**
 * One cut of the record, market by market or model by model.
 *
 * The headline hit rate averages away the thing worth acting on. A model can be
 * honest about corners and badly overconfident about a single goal line, and the
 * combined figure looks healthy while that line keeps losing. This is the table
 * that shows it, and the only change to betting behaviour the app can honestly
 * suggest: back the rows that deliver what they promise, leave the ones that
 * don't.
 */
@Composable
private fun SliceCard(title: String, subtitle: String, slices: List<com.skorsnap.app.data.Slice>) {
    Card(title = title, subtitle = subtitle) {
        Row {
            Text("", Modifier.weight(1.6f))
            Text(
                "Tembus",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
            )
            Text(
                "Janji",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
            )
            Text(
                "Selisih",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.height(6.dp))
        slices.forEach { slice ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1.6f)) {
                    Text(slice.name, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "${slice.won}/${slice.total}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${Math.round(slice.actual * 100)}%",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = probColor(slice.actual),
                    textAlign = TextAlign.End,
                )
                Text(
                    "${Math.round(slice.promised * 100)}%",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
                Text(
                    "%+d".format(Math.round(slice.gap * 100)),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (slice.worthWatching) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        slice.total < 5 -> MaterialTheme.colorScheme.onSurfaceVariant
                        slice.gap <= -0.15 -> Rose
                        slice.gap >= 0.15 -> Green
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.End,
                )
            }
        }
        val watch = slices.filter { it.worthWatching && it.gap < 0 }
        if (watch.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Layak diawasi: " + watch.joinToString { it.name } +
                    " — hasilnya jauh di bawah yang dijanjikan. Belum tentu kebetulan, " +
                    "belum tentu juga bukan; kalau polanya bertahan setelah 20-an taruhan, " +
                    "hindari saja market itu.",
                style = MaterialTheme.typography.bodySmall,
                color = Amber,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Baris dengan kurang dari 5 hasil belum berarti apa-apa — dibiarkan abu-abu.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
