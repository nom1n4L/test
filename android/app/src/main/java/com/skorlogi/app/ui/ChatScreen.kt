package com.skorlogi.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.skorlogi.app.data.ChatMessage

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    busy: Boolean,
    hasKey: Boolean,
    onSend: (String) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (!hasKey) {
        NoKeyNotice(onOpenSettings)
        return
    }

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item { Intro() }
            }
            items(messages.size) { i ->
                Bubble(messages[i])
            }
            if (busy) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            Modifier.width(14.dp).height(14.dp),
                            strokeWidth = 2.dp,
                            color = Green,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Sedang berpikir…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (messages.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.padding(start = 8.dp)) {
                Text("Mulai obrolan baru", style = MaterialTheme.typography.labelSmall)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Tanya apa saja soal prediksinya…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onSend(draft)
                    draft = ""
                },
                enabled = !busy && draft.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Green),
            ) {
                Text("Kirim")
            }
        }
    }
}

@Composable
private fun Intro() {
    SectionCard(
        title = "Tanya Claude",
        subtitle = "Terhubung ke angka aplikasi ini, bukan ke ingatannya sendiri.",
    ) {
        Text(
            "Claude di sini hanya boleh memakai angka yang dihitung aplikasi ini — " +
                "pilihan hari ini, detail pertandingan yang sedang kamu buka, rapor " +
                "pelacakmu, dan hitungan parlaynya. Ia dilarang mengarang statistik " +
                "dari ingatannya, karena pengetahuan sepak bolanya sudah basi dan " +
                "belum tentu benar.\n\n" +
                "Kalau kamu tanya sesuatu yang datanya tidak ada, ia akan bilang tidak " +
                "ada — bukan menebak.\n\n" +
                "Coba tanya:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        listOf(
            "Dari pilihan hari ini, mana yang paling masuk akal?",
            "Kenapa model menjagokan tim ini?",
            "Kalau aku parlay 4 leg dari daftar itu, peluangnya berapa?",
            "Apa bedanya double chance sama draw no bet?",
        ).forEach {
            Text(
                "• $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Bubble(message: ChatMessage) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (message.fromUser) {
                Green.copy(alpha = 0.16f)
            } else {
                MaterialTheme.colorScheme.surface
            },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Text(
                message.text,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun NoKeyNotice(onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Chatbot butuh kunci Claude API",
                style = MaterialTheme.typography.titleMedium,
                color = Amber,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Berbeda dari fitur lain di aplikasi ini, yang ini tidak gratis: tiap " +
                    "pesan dikenakan biaya ke akun Anthropic-mu. Untuk obrolan biasa " +
                    "biayanya kecil, tapi tetap ada.\n\n" +
                    "Daftar di console.anthropic.com, buat kunci API, lalu tempel di " +
                    "Pengaturan. Kamu juga bisa memilih model yang lebih murah di sana.\n\n" +
                    "Semua fitur lain tetap jalan tanpa ini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(containerColor = Green),
            ) {
                Text("Buka Pengaturan")
            }
        }
    }
}
