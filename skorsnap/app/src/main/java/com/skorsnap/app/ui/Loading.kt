package com.skorsnap.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What the app is doing while it makes the user wait.
 *
 * A spinner says "wait" and nothing else, which for a request that genuinely takes
 * half a minute reads as a hang. These are the real stages of the work in the order
 * they happen, so the wait has a shape: the user can see the screenshots being read
 * before any prediction exists, and that the bookmaker's prices are folded in after
 * the probabilities rather than before — which is the honesty rule the whole app is
 * built around, stated where it is least likely to be read as marketing.
 *
 * The timings are approximate and say so. Claiming a precise percentage the app
 * cannot measure would be a lie told by a progress bar.
 */
private val STAGES = listOf(
    "Membaca angka dari screenshot…" to Sky,
    "Menghitung perkiraan gol kedua tim…" to Sky,
    "Menyusun peluang tiap market…" to Violet,
    "Menyalin harga dari kupon bandar…" to Amber,
    "Membandingkan dengan rekor akurasimu…" to Green,
    "Memilih market yang paling layak…" to Green,
)

/** Roughly how long each stage is given before the caption moves on. */
private const val STAGE_MILLIS = 4200L

/** The other job: transcribing a finished match off a screenshot. */
private val RESULT_STAGES = listOf(
    "Membaca skor akhir dari gambar…" to Sky,
    "Mencari skor babak pertama dan corner…" to Sky,
    "Menyelesaikan tiap market dari skor itu…" to Violet,
    "Mencatat hasilnya ke rekor akurasimu…" to Green,
)

@Composable
fun AnalysingScreen(strict: Boolean = false, result: Boolean = false) {
    val stages = if (result) RESULT_STAGES else STAGES
    var stage by remember(result) { mutableIntStateOf(0) }
    LaunchedEffect(result) {
        while (true) {
            delay(STAGE_MILLIS)
            // Holds on the last line rather than looping back to the first. A
            // caption that returns to "reading screenshots" after a minute would
            // suggest the work had restarted.
            if (stage < stages.lastIndex) stage++ else break
        }
    }

    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "angle",
    )
    val pulse by spin.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
            // Two counter-rotating arcs. Cheap to draw, and the motion is continuous
            // rather than stepped, so a slow network never looks like a frozen frame.
            Box(
                Modifier.size(132.dp).rotate(angle).clip(CircleShape).background(
                    Brush.sweepGradient(
                        listOf(Color.Transparent, Violet, Sky, Color.Transparent)
                    )
                )
            )
            Box(
                Modifier.size(112.dp).rotate(-angle * 0.6f).clip(CircleShape).background(
                    Brush.sweepGradient(
                        listOf(Color.Transparent, Green.copy(alpha = 0.7f), Color.Transparent)
                    )
                )
            )
            Box(
                Modifier.size(96.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (result) "📸" else if (strict) "🔒" else "⚽",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.scale(pulse),
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Text(
            when {
                result -> "MEMBACA HASIL"
                strict -> "MODE PALING AMAN"
                else -> "MENGANALISIS"
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (strict && !result) Green else Sky,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(12.dp))

        // One line at a time, faded in. Stacking every stage would turn the wait
        // into a wall of text nobody reads.
        val (caption, tint) = stages[stage]
        val fade by animateFloatAsState(
            targetValue = 1f,
            animationSpec = tween(420),
            label = "fade$stage",
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            modifier = Modifier.alpha(fade),
        )

        Spacer(Modifier.height(20.dp))
        StageBar(stage, stages)

        Spacer(Modifier.height(20.dp))
        Text(
            if (result) {
                "Satu screenshot hasil menyelesaikan puluhan market sekaligus — " +
                    "tidak perlu dicentang satu-satu."
            } else if (strict) {
                "Mode ini menolak market yang tidak lolos semua pemeriksaan, jadi " +
                    "wajar kalau hasilnya nanti menyuruh lewati laga ini."
            } else {
                "Biasanya 20-40 detik. Gambar yang panjang memang lebih lama — " +
                    "dipotong dulu jadi beberapa bagian supaya angkanya tetap terbaca."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One segment per stage, filling as the work moves through them. */
@Composable
private fun StageBar(stage: Int, stages: List<Pair<String, Color>>) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        stages.indices.forEach { i ->
            val done = i <= stage
            val width by animateFloatAsState(
                targetValue = if (done) 1f else 0.35f,
                animationSpec = tween(500),
                label = "seg$i",
            )
            Surface(
                color = if (done) stages[i].second.copy(alpha = 0.85f)
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(3.dp),
                modifier = Modifier.weight(1f).height(5.dp).alpha(width),
            ) {}
        }
    }
}

/**
 * The first thing shown at launch, while saved matches are read off disk.
 *
 * Short by design — this is not a place to put a brand. It exists so the app opens
 * onto something deliberate instead of a blank frame that flashes before the list
 * arrives.
 */
@Composable
fun SplashScreen() {
    val t = rememberInfiniteTransition(label = "splash")
    val glow by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse,
        ),
        label = "glow",
    )
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "SKORSNAP",
            style = MaterialTheme.typography.headlineMedium,
            color = Sky,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.alpha(glow),
        )
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.width(64.dp).height(3.dp).clip(RoundedCornerShape(2.dp))
                .background(Brush.horizontalGradient(listOf(Violet, Sky, Green)))
                .alpha(glow)
        )
    }
}
