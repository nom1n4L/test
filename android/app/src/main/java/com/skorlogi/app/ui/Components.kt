package com.skorlogi.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** A labelled probability row with a proportional bar behind the number. */
@Composable
fun ProbRow(
    label: String,
    prob: Double,
    trailing: String? = null,
    highlight: Boolean = false,
) {
    val pct = (prob * 100).roundToInt()
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "$pct%",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = probColor(prob),
            )
        }
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(prob.coerceIn(0.0, 1.0).toFloat())
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(probColor(prob))
            )
        }
    }
}

/** The three-part 1X2 bar used on fixture cards. */
@Composable
fun TripleBar(home: Double, draw: Double, away: Double, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        Box(Modifier.weight(home.toFloat().coerceAtLeast(0.001f)).fillMaxWidth().background(Green))
        Box(
            Modifier.weight(draw.toFloat().coerceAtLeast(0.001f)).fillMaxWidth()
                .background(MaterialTheme.colorScheme.onSurfaceVariant)
        )
        Box(Modifier.weight(away.toFloat().coerceAtLeast(0.001f)).fillMaxWidth().background(Sky))
    }
}

@Composable
fun Chip(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, filled: Boolean = false) {
    Surface(
        color = if (filled) color.copy(alpha = 0.16f) else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        modifier = if (filled) Modifier else Modifier.border(
            1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)
        ),
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Form string rendered as coloured M / S / K pills. */
@Composable
fun FormPills(form: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        // Newest last reads more naturally as a timeline.
        form.reversed().forEach { c ->
            val color = when (c) {
                'M' -> Green
                'S' -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> Rose
            }
            Box(
                Modifier.size(19.dp).clip(CircleShape).background(color.copy(alpha = 0.20f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(c.toString(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}
