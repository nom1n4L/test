package com.skorlogi.app.data

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ChatMessage(val fromUser: Boolean, val text: String)

/**
 * The conversational assistant.
 *
 * The design decision that matters here is grounding. A language model asked about
 * football will happily produce plausible statistics it has invented, and in an app
 * whose entire argument is that its numbers are measured, that would be corrosive.
 * So the model is never asked to estimate anything: the app computes the figures
 * and passes them in, and the system prompt's first rule is that no number may
 * appear in an answer unless it appeared in that context.
 *
 * It is also told the uncomfortable findings — that the bookmaker out-predicts this
 * model, that parlays lose 6% per leg on average — because an assistant that talks
 * a user into a bet the app's own backtest argues against would be worse than no
 * assistant at all.
 */
class Assistant(private val apiKey: String) {

    class AssistantException(message: String) : Exception(message)

    suspend fun ask(
        history: List<ChatMessage>,
        context: String,
        model: String = DEFAULT_MODEL,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AssistantException("Kunci Claude API belum diisi.")

        try {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            val builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(4000L)
                .system(systemPrompt(context))
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())

            for (m in history) {
                if (m.fromUser) builder.addUserMessage(m.text) else builder.addAssistantMessage(m.text)
            }

            val response = client.messages().create(builder.build())
            val text = response.content()
                .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString("\n")
                .trim()

            if (text.isEmpty()) "Tidak ada jawaban yang bisa ditampilkan." else text
        } catch (e: AssistantException) {
            throw e
        } catch (e: Exception) {
            throw AssistantException(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun systemPrompt(context: String): String = """
Kamu asisten di dalam aplikasi Skorlogi, aplikasi prediksi sepak bola berbahasa
Indonesia. Jawab selalu dalam bahasa Indonesia yang santai tapi jelas.

ATURAN PALING PENTING — ANGKA:
Kamu TIDAK BOLEH menyebut angka statistik apa pun yang tidak ada di bagian DATA
di bawah. Jangan mengarang rata-rata gol, peluang, rekor, harga odds, atau
klasemen dari ingatanmu — pengetahuanmu soal sepak bola sudah kedaluwarsa dan
aplikasi ini justru dibangun di atas angka terukur. Kalau pengguna menanyakan
sesuatu yang datanya tidak ada di bawah, katakan terus terang bahwa datanya tidak
tersedia di aplikasi, lalu sarankan mereka membuka halaman yang memuatnya.

YANG PERLU KAMU TAHU TENTANG MODELNYA:
- Prediksi dihitung dengan Dixon-Coles (Poisson berbobot waktu) plus campuran Elo
  20%, lalu dikalibrasi dengan Platt scaling dari musim yang ditahan.
- Diuji ulang pada 2.770 pertandingan: hasil akhir benar 51,8%, over/under 2.5
  benar 56,4%, babak 1 ada gol benar 72,5%.
- Sebagai pembanding pada laga yang sama: menebak tuan rumah terus benar 43,8%,
  dan bandar Bet365 (margin dibuang) benar 53,9% dengan log loss 0,9742 melawan
  0,9909 milik model ini. Artinya BANDAR LEBIH AKURAT daripada model ini.
- Market corner dan kartu gagal uji kalibrasi: yang mengaku 74% ternyata cuma
  benar 55%. Angkanya sudah ditarik mendekati 50% dan market itu tidak pernah
  dipakai di halaman Pilihan Terbaik.
- Margin bandar terukur 6,03% per leg (dari 7.314 harga 1X2 Bet365).

SIKAP SOAL PARLAY:
Kalau pengguna bertanya soal parlay, sampaikan aritmetikanya apa adanya:
peluang tiap leg dikalikan (4 leg @80% = 41%, bukan 80%), dan margin bandar juga
dikalikan sehingga imbal hasil harapan parlay n-leg adalah 1/1,0603^n berapa pun
legnya — 4 leg berarti rugi sekitar 21% rata-rata. Tidak ada parlay yang "aman".
Jangan menakut-nakuti dan jangan menggurui; sebutkan sekali, dengan tenang, lalu
tetap bantu mereka membaca angkanya. Keputusan taruhan ada di tangan mereka.

GAYA:
Ringkas. Jawab pertanyaannya, jangan berceramah. Kalau kamu tidak yakin, katakan
tidak yakin. Jangan pernah menjanjikan kemenangan.

DATA SAAT INI:
$context
""".trim()

    companion object {
        /** Anthropic's most capable general model; the user can pick a cheaper one. */
        const val DEFAULT_MODEL = "claude-opus-5"

        /** Label, model id, and rough cost per 1M tokens in and out. */
        val MODELS = listOf(
            Triple("Opus 5 — paling pintar", "claude-opus-5", "\$5 / \$25 per 1 juta token"),
            Triple("Sonnet 5 — lebih murah", "claude-sonnet-5", "\$2 / \$10 per 1 juta token"),
            Triple("Haiku 4.5 — paling murah", "claude-haiku-4-5", "\$1 / \$5 per 1 juta token"),
        )
    }
}
