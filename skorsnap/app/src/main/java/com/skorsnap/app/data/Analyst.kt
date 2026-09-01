package com.skorsnap.app.data

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ThinkingConfigAdaptive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Base64

/**
 * Reads a match's statistics out of screenshots and turns them into probabilities.
 *
 * The whole app rests on one rule, which is also the only thing that makes reading
 * screenshots better than guessing: nothing may be used that is not visible in the
 * pictures. A language model asked about football will produce confident-sounding
 * numbers from its own stale memory, and those numbers would be indistinguishable
 * on screen from ones actually derived from the user's data. So the prompt forbids
 * it, asks for the stats it did read to be listed back, and asks for the ones it
 * expected and could not find — which is what lets the app show its work.
 */
class Analyst(private val apiKey: String) {

    class AnalystException(message: String) : Exception(message)

    suspend fun analyse(
        images: List<ByteArray>,
        note: String,
        model: String = DEFAULT_MODEL,
    ): MatchPrediction = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AnalystException("Kunci Claude API belum diisi.")
        if (images.isEmpty()) throw AnalystException("Belum ada gambar.")

        val blocks = ArrayList<ContentBlockParam>(images.size + 1)
        for (bytes in images) {
            blocks.add(
                ContentBlockParam.ofImage(
                    ImageBlockParam.builder()
                        .source(
                            Base64ImageSource.builder()
                                .mediaType(mediaTypeOf(bytes))
                                .data(Base64.getEncoder().encodeToString(bytes))
                                .build()
                        )
                        .build()
                )
            )
        }
        blocks.add(
            ContentBlockParam.ofText(
                TextBlockParam.builder().text(userPrompt(note)).build()
            )
        )

        val text = try {
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            val response = client.messages().create(
                MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(8000L)
                    .system(SYSTEM_PROMPT)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.HIGH).build())
                    .addUserMessageOfBlockParams(blocks)
                    .build()
            )
            response.content()
                .mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
                .joinToString("\n")
                .trim()
        } catch (e: AnalystException) {
            throw e
        } catch (e: Exception) {
            throw AnalystException(e.message ?: e.javaClass.simpleName)
        }

        parse(text)
    }

    /** Sniffs the format from the file's own header rather than trusting a name. */
    private fun mediaTypeOf(bytes: ByteArray): Base64ImageSource.MediaType = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() ->
            Base64ImageSource.MediaType.IMAGE_JPEG
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() ->
            Base64ImageSource.MediaType.IMAGE_PNG
        bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() ->
            Base64ImageSource.MediaType.IMAGE_WEBP
        else -> Base64ImageSource.MediaType.IMAGE_JPEG
    }

    /**
     * Pulls the JSON object out of the reply. The model is asked for JSON and
     * nothing else, but a stray sentence before it should not cost the user their
     * analysis, so the object is located rather than assumed to start at index 0.
     */
    internal fun parse(text: String): MatchPrediction {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw AnalystException("Balasan tidak berbentuk JSON. Isi balasan: ${text.take(200)}")
        }
        val json = try {
            JSONObject(text.substring(start, end + 1))
        } catch (e: Exception) {
            throw AnalystException("JSON tidak bisa dibaca: ${e.message}")
        }

        fun strings(key: String): List<String> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }

        val markets = ArrayList<MarketOption>()
        json.optJSONArray("markets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val name = m.optString("name")
                val prob = m.optDouble("prob", -1.0)
                if (name.isBlank() || prob < 0 || prob > 1) continue
                markets.add(MarketOption(name, prob, m.optString("why")))
            }
        }

        return MatchPrediction(
            id = java.util.UUID.randomUUID().toString(),
            home = json.optString("home"),
            away = json.optString("away"),
            league = json.optString("league"),
            readable = json.optBoolean("readable", true),
            problem = json.optString("problem"),
            statsSeen = strings("stats_seen"),
            statsMissing = strings("stats_missing"),
            probHome = json.optDouble("prob_home", 0.0),
            probDraw = json.optDouble("prob_draw", 0.0),
            probAway = json.optDouble("prob_away", 0.0),
            xgHome = json.optDouble("xg_home", 0.0),
            xgAway = json.optDouble("xg_away", 0.0),
            markets = markets.sortedByDescending { it.prob },
            pick = json.optString("pick"),
            pickProb = json.optDouble("pick_prob", 0.0),
            confidence = json.optString("confidence", "sedang"),
            confidenceWhy = json.optString("confidence_why"),
            raw = text,
        )
    }

    private fun userPrompt(note: String): String = buildString {
        append("Baca statistik di gambar-gambar di atas, lalu balas HANYA dengan satu objek JSON. ")
        append("Tanpa kalimat pembuka, tanpa blok kode, tanpa penjelasan di luar JSON.\n\n")
        if (note.isNotBlank()) append("Catatan dari pengguna: $note\n\n")
        append(
            """
Bentuk JSON-nya persis seperti ini:

{
  "home": "nama tim tuan rumah",
  "away": "nama tim tandang",
  "league": "nama liga kalau terlihat",
  "readable": true,
  "problem": "diisi hanya kalau gambar tidak terbaca atau statistiknya terlalu sedikit",
  "stats_seen": ["daftar statistik yang benar-benar kamu baca dari gambar"],
  "stats_missing": ["statistik penting yang kamu cari tapi tidak ada di gambar"],
  "prob_home": 0.00,
  "prob_draw": 0.00,
  "prob_away": 0.00,
  "xg_home": 0.0,
  "xg_away": 0.0,
  "markets": [
    {"name": "nama market, mis. Over 1.5", "prob": 0.00, "why": "alasan singkat dari angka di gambar"}
  ],
  "pick": "market yang paling layak dipilih",
  "pick_prob": 0.00,
  "confidence": "tinggi | sedang | rendah",
  "confidence_why": "kenapa segitu"
}

Aturan:
- prob_home + prob_draw + prob_away harus berjumlah 1,0.
- Semua "prob" adalah peluang, bukan persen. 0,72 berarti 72%.
- Isi "markets" dengan 4-8 market yang datanya memang ada di gambar.
- "pick" dipilih dari "markets", yaitu yang paling seimbang antara peluang tinggi
  dan dukungan data yang jelas. Jangan pilih yang peluangnya di atas 0,92 —
  odds-nya terlalu kecil untuk dipasang.
- Semua teks dalam bahasa Indonesia.
            """.trimIndent()
        )
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5"

        val MODELS = listOf(
            Triple("Opus 5 — paling teliti baca gambar", "claude-opus-5", "$5 / $25 per 1 juta token"),
            Triple("Sonnet 5 — lebih murah", "claude-sonnet-5", "$2 / $10 per 1 juta token"),
        )

        private val SYSTEM_PROMPT = """
Kamu menganalisis statistik sepak bola dari tangkapan layar untuk aplikasi Skorsnap.
Semua jawaban dalam bahasa Indonesia.

ATURAN YANG TIDAK BOLEH DILANGGAR:

1. Hanya pakai angka yang benar-benar terlihat di gambar. Pengetahuan sepak bolamu
   sendiri sudah kedaluwarsa dan tidak boleh dipakai — jangan menambahkan rata-rata
   gol, rekor, cedera, atau klasemen dari ingatan. Kalau sesuatu tidak ada di
   gambar, tulis di "stats_missing", jangan dikarang.

2. Kalau gambarnya buram, terpotong, atau isinya bukan statistik sepak bola, set
   "readable": false dan jelaskan di "problem". Jangan memaksakan tebakan.

3. Jujurlah soal keyakinan. Statistik sepak bola punya batas: bahkan model terbaik
   dengan data lengkap hanya benar sekitar 52-55% untuk tebakan menang/seri/kalah.
   Kalau kamu memberi peluang 85% untuk hasil akhir, kemungkinan besar kamu salah.
   Angka setinggi itu hanya wajar untuk market seperti "over 0.5 gol".

4. Jangan pernah menulis bahwa sesuatu pasti menang, aman, atau dijamin. Yang kamu
   berikan adalah peluang, dan peluang 80% tetap meleset 1 dari 5 kali.

5. Untuk "pick", pilih market yang paling didukung angka di gambar — bukan yang
   peluangnya paling besar. Market seperti Over/Under, Double Chance, dan Kedua Tim
   Cetak Gol biasanya lebih bisa diandalkan daripada tebakan skor akhir.

Balas hanya dengan JSON sesuai bentuk yang diminta pengguna.
        """.trimIndent()
    }
}
