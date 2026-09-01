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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.Dates
import com.skorlogi.app.data.Fixture
import com.skorlogi.app.data.Leagues
import com.skorlogi.app.engine.Confidence
import com.skorlogi.app.engine.Pick

/**
 * The screen that answers the only question the user actually has: what should I
 * back today, and is the price worth it.
 *
 * Earlier versions opened with several paragraphs explaining what the list would
 * not do. That reasoning is still right and still here, but it was standing in
 * front of the answer instead of behind it, so it now lives under a tap.
 */
@Composable
fun PicksScreen(
    offers: List<PickOffer>,
    myBookmaker: String,
    working: Boolean,
    blocked: Boolean,
    isFollowed: (Pick) -> Boolean,
    onFollow: (Pick) -> Unit,
    onAddToParlay: (Pick) -> Unit,
    onOpen: (Fixture) -> Unit,
    onUseFallback: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenParlay: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (blocked) {
            item { BlockedNotice(onUseFallback, onOpenSettings) }
        }

        item { Header(offers, myBookmaker, onOpenParlay) }

        if (offers.isEmpty() && !blocked) {
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
                            "Tidak ada laga yang lolos saringan hari ini.\n\n" +
                                "Saringannya menuntut peluang 68–92% dari market yang " +
                                "terbukti jujur. Hari sepi memang bisa kosong.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                }
            }
        }

        items(offers, key = { "${it.pick.fixture.key}|${it.pick.kind.name}" }) { offer ->
            OfferCard(
                offer = offer,
                myBookmaker = myBookmaker,
                followed = isFollowed(offer.pick),
                onFollow = onFollow,
                onAddToParlay = onAddToParlay,
                onOpen = onOpen,
            )
        }
    }
}

@Composable
private fun Header(offers: List<PickOffer>, myBookmaker: String, onOpenParlay: () -> Unit) {
    var showWhy by remember { mutableStateOf(false) }
    val priced = offers.count { it.myPrice != null }
    val worth = offers.count { it.worthIt }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("Rekomendasi Hari Ini", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                when {
                    offers.isEmpty() -> "Belum ada."
                    priced == 0 ->
                        "${offers.size} pilihan. Harga $myBookmaker belum diambil — " +
                            "buka tab Odds untuk melihat mana yang layak dipasang."
                    else ->
                        "${offers.size} pilihan, $priced ada harganya di $myBookmaker, " +
                            "$worth di antaranya harganya layak."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenParlay) {
                    Text("Susun parlay", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { showWhy = !showWhy }) {
                    Text(
                        if (showWhy) "Tutup penjelasan" else "Kenapa cuma segini?",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            AnimatedVisibility(showWhy) {
                Text(
                    "Dari semua market yang bisa dihitung, hanya empat yang lolos uji " +
                        "kejujuran — Double Chance, Hasil Akhir, Total Gol, dan Babak 1 — " +
                        "karena saat diuji ulang, angka yang mereka sebut memang " +
                        "kira-kira sebesar itu kejadiannya.\n\n" +
                        "Corner dan kartu tidak pernah muncul: prediksi corner yang " +
                        "mengaku 70%+ ternyata cuma benar sekitar setengahnya.\n\n" +
                        "Peluang di atas 92% juga dibuang, karena odds-nya di bawah 1,10 " +
                        "dan tidak ada bandar yang menawarkannya.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun OfferCard(
    offer: PickOffer,
    myBookmaker: String,
    followed: Boolean,
    onFollow: (Pick) -> Unit,
    onAddToParlay: (Pick) -> Unit,
    onOpen: (Fixture) -> Unit,
) {
    val pick = offer.pick
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Leagues.byCode(pick.fixture.league)?.name ?: pick.fixture.league,
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
            Spacer(Modifier.height(4.dp))
            Text(
                "${pick.fixture.home} vs ${pick.fixture.away}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        pick.market,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "${pick.percent}%",
                        style = MaterialTheme.typography.titleLarge,
                        color = probColor(pick.prob),
                    )
                    Text(
                        if (pick.confidence == Confidence.HIGH) "data tebal" else "data cukup",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))
            PriceVerdict(offer, myBookmaker)

            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { onAddToParlay(pick) }) {
                    Text("+ Parlay", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { onFollow(pick) }, enabled = !followed) {
                    Text(
                        if (followed) "Sudah dicatat ✓" else "Catat",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * The line that turns a probability into a decision. A pick is only worth backing
 * when the price on offer pays more than the probability says it should, so the
 * break-even price is shown next to the real one rather than left as an exercise.
 */
@Composable
private fun PriceVerdict(offer: PickOffer, myBookmaker: String) {
    val my = offer.myPrice
    if (my == null) {
        Text(
            "Harga di $myBookmaker belum ada. Minimal harus %.2f supaya taruhan ini " +
                "layak — ambil odds di tab Odds untuk membandingkan."
                .format(offer.breakEvenOdds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val ret = offer.myReturn ?: 0.0
    val good = offer.worthIt
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Harga $myBookmaker",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "%.2f".format(my),
                style = MaterialTheme.typography.titleMedium,
                color = if (good) Green else Rose,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "Impas di",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("%.2f".format(offer.breakEvenOdds), style = MaterialTheme.typography.titleMedium)
        }
        Surface(
            color = (if (good) Green else Rose).copy(alpha = 0.16f),
            shape = RoundedCornerShape(7.dp),
        ) {
            Text(
                if (good) "LAYAK  %+.0f%%".format((ret - 1) * 100) else "KURANG %+.0f%%".format((ret - 1) * 100),
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (good) Green else Rose,
            )
        }
    }

    if (offer.betterElsewhere) {
        Spacer(Modifier.height(8.dp))
        Surface(
            color = Sky.copy(alpha = 0.12f),
            shape = RoundedCornerShape(9.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "%s membayar %.2f untuk taruhan yang sama — %.0f%% lebih tinggi."
                    .format(offer.bestBook, offer.bestPrice, ((offer.bestPrice!! / my) - 1) * 100),
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = Sky,
            )
        }
    }
}
