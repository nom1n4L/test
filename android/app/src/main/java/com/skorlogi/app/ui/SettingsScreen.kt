package com.skorlogi.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skorlogi.app.data.Dates
import kotlin.math.ln
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: AppViewModel,
    state: UiState,
    decay: Double,
    onSync: () -> Unit,
    onDecay: (Double) -> Unit,
    onOpenLeagues: () -> Unit,
) {
    var value by remember { mutableFloatStateOf(decay.toFloat()) }
    val halfLifeDays = (ln(2.0) / value.coerceAtLeast(0.0005f)).roundToInt()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionCard(
            title = "Data",
            subtitle = if (state.lastSync < 0) {
                "Belum pernah diperbarui."
            } else {
                "Terakhir diperbarui ${Dates.format(state.lastSync)} · ${state.matchCount} pertandingan tersimpan."
            },
        ) {
            Button(
                onClick = onSync,
                enabled = !state.syncing,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.syncing) "Sedang mengunduh…" else "Perbarui Data Sekarang")
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Sumber: football-data.co.uk — arsip terbuka, tanpa akun dan tanpa kunci API. " +
                    "Berkasnya diperbarui pemiliknya beberapa kali seminggu, biasanya tiap Minggu dan Rabu.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ApiSection(vm)

        SectionCard(title = "Liga") {
            Button(
                onClick = onOpenLeagues,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Pilih liga yang diikuti", color = MaterialTheme.colorScheme.onSurface)
            }
        }

        SectionCard(
            title = "Bobot Laga Lama",
            subtitle = "Laga lama dianggap kurang mewakili kekuatan tim sekarang. " +
                "Sekarang: pengaruh sebuah laga tinggal separuh setelah $halfLifeDays hari.",
        ) {
            Slider(
                value = value,
                onValueChange = { value = it },
                onValueChangeFinished = { onDecay(value.toDouble()) },
                valueRange = 0.002f..0.012f,
                colors = SliderDefaults.colors(thumbColor = Green, activeTrackColor = Green),
            )
            Row {
                Text(
                    "Lebih banyak riwayat",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Lebih reaktif",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Nilai bawaan diambil dari hasil uji ulang: pada rentang 4 bulan sampai 1 tahun " +
                    "hasilnya hampir sama saja, jadi tidak perlu diutak-atik. Yang jelas menurun " +
                    "cuma kalau digeser sampai mentok ke sisi paling reaktif.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Cara Kerja Model") {
            Text(
                "1. Mengunduh seluruh hasil pertandingan beberapa musim terakhir dari liga yang kamu pilih.\n\n" +
                    "2. Untuk tiap liga, mencari nilai serangan dan pertahanan tiap tim yang paling " +
                    "cocok dengan hasil nyata, dengan laga terbaru diberi bobot lebih besar " +
                    "(metode Dixon–Coles, 1997).\n\n" +
                    "3. Dari nilai itu dihitung perkiraan gol kedua tim, lalu disusun tabel peluang " +
                    "untuk setiap kemungkinan skor.\n\n" +
                    "4. Semua market — 1X2, over/under, handicap, corner, kartu, babak 1 — dibaca " +
                    "dari tabel yang sama, jadi angkanya selalu konsisten satu sama lain.\n\n" +
                    "5. Elo dihitung terpisah sebagai pembanding. Kalau keduanya berbeda jauh, " +
                    "keyakinan prediksi diturunkan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Sejujurnya soal Akurasi") {
            Text(
                "Model ini diuji ulang pada 2.709 pertandingan yang belum pernah dilihatnya, " +
                    "dari 7 liga. Tiap prediksi hanya memakai data yang benar-benar sudah " +
                    "ada saat itu, jadi tidak ada hasil yang bocor dari masa depan.\n\n" +
                    "Hasilnya:\n" +
                    "• Hasil akhir (1X2): benar 52,0%\n" +
                    "• Over/Under 2.5 gol: benar 55,9%\n" +
                    "• Kedua tim cetak gol: benar 55,8%\n" +
                    "• Babak 1 ada gol: benar 72,2%\n\n" +
                    "Sebagai pembanding: menebak tuan rumah menang terus cuma benar 43,7%, " +
                    "sedangkan bandar Bet365 — dengan margin dibuang — benar 53,9%. " +
                    "Jadi model ini kira-kira 2 poin di bawah bandar, dan jauh di atas tebakan asal.\n\n" +
                    "Angka 52% itu memang sudah bagus untuk sepak bola. Olahraga dengan gol " +
                    "sedikit punya unsur keberuntungan besar yang tidak bisa dihapus model apa pun. " +
                    "Siapa pun yang menjanjikan akurasi 90% sedang menjual sesuatu.\n\n" +
                    "Yang tidak diketahui aplikasi ini: cedera, rotasi pemain, larangan bermain, " +
                    "pergantian pelatih, dan motivasi tim. Pakailah sebagai satu masukan, " +
                    "bukan satu-satunya.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Kejujuran Angka") {
            Text(
                "Tiap market diuji ulang untuk melihat apakah angkanya bisa dipegang: " +
                    "kalau model bilang 75%, apakah benar terjadi 75%?\n\n" +
                    "Yang lolos: Hasil Akhir, Double Chance, Total Gol, Babak 1. " +
                    "Angka mereka meleset paling banyak beberapa poin.\n\n" +
                    "Yang tidak lolos: corner dan kartu. Sebelum diperbaiki, prediksi corner " +
                    "yang mengaku 74% ternyata cuma benar 55%. Sekarang angkanya sudah " +
                    "ditarik mendekati 50% supaya tidak menyesatkan, dan market ini tidak " +
                    "pernah dipakai di halaman Pilihan Terbaik.\n\n" +
                    "BTTS juga sempat kelewat percaya diri (klaim 72%, nyata 64%) dan sudah " +
                    "dikoreksi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "Skorlogi 1.2") {
            Text(
                "Semua perhitungan berjalan di dalam HP. Tidak ada akun, tidak ada iklan, " +
                    "tidak ada data yang dikirim ke mana pun selain mengunduh arsip pertandingan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/**
 * API-Football setup. The archive the app ships with covers 38 leagues and about a
 * week of fixtures; a key here adds the rest of the world, Liga 1 included, and a
 * schedule that runs days further ahead.
 */
@Composable
private fun ApiSection(vm: AppViewModel) {
    val status by vm.apiStatus.collectAsStateWithLifecycle()
    val catalog by vm.apiCatalog.collectAsStateWithLifecycle()
    val busy by vm.apiBusy.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf(vm.repo.apiKey) }
    val followed = remember(catalog, status) { vm.followedApiLeagues() }

    SectionCard(
        title = "Tambah Liga Dunia (API-Football)",
        subtitle = "Opsional. Tanpa ini, aplikasi tetap jalan dengan 38 liga dari arsip terbuka.",
    ) {
        Text(
            "Daftar gratis di dashboard.api-football.com, salin kuncinya, tempel di sini. " +
                "Kuota gratisnya kecil, jadi aplikasi ini menyimpan hasilnya dan hanya " +
                "mengambil ulang riwayat seminggu sekali.\n\n" +
                "Catatan jujur: data corner dan kartu tidak ikut lewat jalur ini — di API itu " +
                "biayanya satu permintaan per pertandingan, yang mustahil muat di kuota gratis. " +
                "Market itu tetap datang dari arsip untuk 22 liga yang punya datanya.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = key,
            onValueChange = { key = it.trim() },
            label = { Text("Kunci API") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.testApiKey(key) },
                enabled = !busy && key.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
            ) {
                Text(if (busy) "Mengecek…" else "Cek kunci")
            }
            Button(
                onClick = { vm.loadApiCatalog() },
                enabled = !busy && vm.repo.hasApiKey,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
            ) {
                Text("Ambil daftar liga", color = MaterialTheme.colorScheme.onSurface)
            }
        }
        if (status != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                status!!,
                style = MaterialTheme.typography.bodySmall,
                color = if (status!!.startsWith("Gagal")) Rose else Green,
            )
        }
        if (followed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Diikuti lewat API: ${followed.size} liga — ${followed.take(4).joinToString { it.name }}" +
                    if (followed.size > 4) ", dan lainnya" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (catalog.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ApiLeaguePicker(catalog, followed) { vm.setFollowedApiLeagues(it) }
        }
    }
}

/**
 * Picks from the API catalogue. It runs to well over a thousand entries, so this
 * filters by typed text rather than trying to list them all.
 */
@Composable
private fun ApiLeaguePicker(
    catalog: List<com.skorlogi.app.data.ApiLeague>,
    followed: List<com.skorlogi.app.data.ApiLeague>,
    onChange: (List<com.skorlogi.app.data.ApiLeague>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember(followed) { mutableStateOf(followed.map { it.id }.toSet()) }

    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Cari liga atau negara") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    val matches = remember(query, catalog) {
        if (query.length < 2) {
            emptyList()
        } else {
            catalog.filter {
                it.name.contains(query, true) || it.country.contains(query, true)
            }.take(30)
        }
    }
    if (query.length < 2) {
        Text(
            "Ketik minimal 2 huruf. Contoh: \"Indonesia\" untuk Liga 1 dan Liga 2.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    matches.forEach { league ->
        val on = league.id in selected
        Row(
            Modifier.fillMaxWidth().padding(vertical = 1.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Checkbox(
                checked = on,
                onCheckedChange = { checked ->
                    selected = if (checked) selected + league.id else selected - league.id
                    onChange(catalog.filter { it.id in selected })
                },
                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Green),
            )
            Column(Modifier.weight(1f)) {
                Text(league.name, style = MaterialTheme.typography.bodySmall)
                Text(
                    "${league.country} · musim ${league.currentSeason}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
