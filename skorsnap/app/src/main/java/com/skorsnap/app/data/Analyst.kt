package com.skorsnap.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Reads a match's statistics out of screenshots and turns them into probabilities,
 * using Google's Gemini API.
 *
 * The whole app rests on one rule, which is also the only thing that makes reading
 * screenshots better than guessing: nothing may be used that is not visible in the
 * pictures. A language model asked about football will produce confident-sounding
 * numbers from its own stale memory, and those numbers would be indistinguishable
 * on screen from ones actually derived from the user's data. So the instructions
 * forbid it, ask for the stats it did read to be listed back, and ask for the ones
 * it expected and could not find — which is what lets the app show its work.
 *
 * Talks to the REST endpoint directly rather than through a client library. The
 * request is one POST with a JSON body, the app was 3.4 MB with an SDK bundled and
 * is 1.3 MB without one, and `responseSchema` means the reply cannot come back in
 * a shape the parser does not expect.
 */
class Analyst(private val apiKey: String) {

    class AnalystException(message: String) : Exception(message)

    /** A model this key can actually use, as reported by Google itself. */
    data class Model(val id: String, val label: String, val description: String)

    /**
     * Asks the API which models this key may call, instead of shipping a guessed
     * list. Model names change and availability differs per key and per tier — a
     * hardcoded name that has been renamed or is not on the free tier fails with a
     * 404 that tells the user nothing they can act on.
     *
     * Free: listing models costs no quota.
     */
    suspend fun listModels(): List<Model> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AnalystException("Kunci Gemini belum diisi.")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$HOST/v1beta/models?pageSize=200").openConnection() as HttpURLConnection)
                .apply {
                    requestMethod = "GET"
                    connectTimeout = 25_000
                    readTimeout = 25_000
                    setRequestProperty("x-goog-api-key", apiKey)
                }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw AnalystException(errorMessage(code, text))

            val arr = JSONObject(text).optJSONArray("models") ?: JSONArray()
            val out = ArrayList<Model>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")
                val supported = (0 until (methods?.length() ?: 0))
                    .any { methods!!.optString(it) == "generateContent" }
                if (!supported) continue

                val name = m.optString("name").removePrefix("models/")
                if (!usable(name)) continue

                out.add(
                    Model(
                        id = name,
                        label = m.optString("displayName").ifBlank { name },
                        description = m.optString("description").take(90),
                    )
                )
            }
            rank(out)
        } catch (e: AnalystException) {
            throw e
        } catch (e: Exception) {
            throw AnalystException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun analyse(
        images: List<ByteArray>,
        note: String,
        model: String = DEFAULT_MODEL,
    ): MatchPrediction = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AnalystException("Kunci Gemini belum diisi.")
        if (images.isEmpty()) throw AnalystException("Belum ada gambar.")

        val parts = JSONArray()
        for (bytes in images) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", mimeTypeOf(bytes))
                        .put("data", Base64.getEncoder().encodeToString(bytes))
                )
            )
        }
        parts.put(JSONObject().put("text", userPrompt(note)))

        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_PROMPT)))
            )
            .put(
                "generationConfig",
                JSONObject()
                    // Reading numbers off a table is not a creative task; near-zero
                    // temperature keeps the same screenshot giving the same answer.
                    .put("temperature", 0.15)
                    .put("maxOutputTokens", 8192)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", RESPONSE_SCHEMA)
            )

        parse(post(model, body.toString()))
    }

    private fun post(model: String, body: String): String {
        var conn: HttpURLConnection? = null
        try {
            val url = "$HOST/v1beta/models/$model:generateContent"
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 30_000
                // Reading several dense screenshots takes the model a while.
                readTimeout = 180_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) throw AnalystException(errorMessage(code, text))

            val json = JSONObject(text)
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AnalystException(
                    json.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "Permintaan ditolak Gemini ($it)." }
                        ?: "Gemini tidak mengembalikan jawaban."
                )

            // A reply cut off mid-JSON parses as garbage; say so plainly instead.
            val finish = candidate.optString("finishReason")
            if (finish == "MAX_TOKENS") {
                throw AnalystException("Jawaban terpotong. Coba kurangi jumlah gambarnya.")
            }

            val partsOut = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw AnalystException("Balasan Gemini kosong (alasan: ${finish.ifBlank { "tidak diketahui" }}).")

            return (0 until partsOut.length())
                .mapNotNull { partsOut.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
                .joinToString("\n")
                .trim()
        } catch (e: AnalystException) {
            throw e
        } catch (e: Exception) {
            throw AnalystException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** Turns Google's status codes into something a user can act on. */
    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return when (code) {
            400 -> if (detail.contains("API key", true)) {
                "Kunci ditolak. Pastikan disalin utuh dari aistudio.google.com."
            } else {
                "Permintaan ditolak: ${detail.take(160)}"
            }
            403 -> "Kunci tidak punya akses. Cek lagi kuncinya di aistudio.google.com."
            404 -> "Model itu tidak ada untuk kuncimu. Buka Pengaturan lalu tekan " +
                "\"Cek model yang tersedia\" — daftarnya diambil langsung dari Google."
            429 -> "Kuota gratis Gemini habis untuk sekarang. Tunggu beberapa menit, atau pilih model Flash yang jatahnya lebih besar."
            in 500..599 -> "Server Gemini sedang bermasalah. Coba lagi sebentar lagi."
            else -> "Gagal (HTTP $code): ${detail.take(160)}"
        }
    }

    /** Sniffs the format from the file's own header rather than trusting a name. */
    private fun mimeTypeOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/jpeg"
    }

    /**
     * Pulls the JSON object out of the reply. The schema should guarantee clean
     * JSON, but a stray sentence around it should not cost the user their
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
        append("Baca statistik di gambar-gambar di atas, lalu isi JSON sesuai skema.\n\n")
        if (note.isNotBlank()) append("Catatan dari pengguna: $note\n\n")
        append(
            """
Aturan pengisian:
- prob_home + prob_draw + prob_away harus berjumlah 1,0.
- Semua "prob" adalah peluang antara 0 dan 1, bukan persen. 0,72 berarti 72%.
- Isi "markets" dengan 4-8 market yang datanya memang terlihat di gambar.
- "pick" harus salah satu dari nama di "markets" — yang paling seimbang antara
  peluang tinggi dan dukungan data yang jelas. Jangan pilih yang peluangnya di
  atas 0,92, karena odds-nya terlalu kecil untuk dipasang.
- "stats_seen" diisi statistik yang benar-benar kamu baca dari gambar.
- "stats_missing" diisi statistik penting yang kamu cari tapi tidak ada di gambar.
- Semua teks dalam bahasa Indonesia.
            """.trimIndent()
        )
    }

    companion object {
        private const val HOST = "https://generativelanguage.googleapis.com"

        const val DEFAULT_MODEL = "gemini-2.5-flash"

        /** Variants built for other jobs entirely. */
        private val SPECIALISED = listOf(
            "embedding", "-tts", "-image", "native-audio", "live-",
            "robotics", "transcribe", "guard", "computer-use", "-thinking-",
        )

        /**
         * Whether a model can plausibly read a screenshot of a stats table. The
         * API also offers embedding, speech, image-generation, robotics and
         * transcription variants, and listing them all buries the three or four
         * that are actually usable.
         */
        internal fun usable(name: String): Boolean =
            name.startsWith("gemini-") && SPECIALISED.none { it in name }

        /** Flash and Pro first — the real choice — then newest within each group. */
        internal fun rank(models: List<Model>): List<Model> = models.sortedWith(
            compareByDescending<Model> { "flash" in it.id || "pro" in it.id }
                .thenByDescending { it.id }
        )

        /** Label, model id, and what to expect. */
        val MODELS = listOf(
            Triple("Gemini 2.5 Flash", "gemini-2.5-flash", "Gratis, cepat — pilihan awal yang baik"),
            Triple("Gemini 3 Flash", "gemini-3-flash", "Lebih baru, biasanya lebih teliti"),
            Triple("Gemini 2.5 Pro", "gemini-2.5-pro", "Paling teliti baca angka, jatah gratisnya lebih kecil"),
        )

        /**
         * Forces the reply into the shape the parser expects, so a malformed answer
         * cannot reach the user as a blank match.
         */
        internal val RESPONSE_SCHEMA: JSONObject = JSONObject()
            .put("type", "OBJECT")
            .put(
                "properties",
                JSONObject()
                    .put("home", str())
                    .put("away", str())
                    .put("league", str())
                    .put("readable", JSONObject().put("type", "BOOLEAN"))
                    .put("problem", str())
                    .put("stats_seen", strArray())
                    .put("stats_missing", strArray())
                    .put("prob_home", num())
                    .put("prob_draw", num())
                    .put("prob_away", num())
                    .put("xg_home", num())
                    .put("xg_away", num())
                    .put(
                        "markets",
                        JSONObject().put("type", "ARRAY").put(
                            "items",
                            JSONObject()
                                .put("type", "OBJECT")
                                .put(
                                    "properties",
                                    JSONObject().put("name", str()).put("prob", num()).put("why", str())
                                )
                                .put("required", JSONArray().put("name").put("prob").put("why"))
                        )
                    )
                    .put("pick", str())
                    .put("pick_prob", num())
                    .put("confidence", str())
                    .put("confidence_why", str())
            )
            .put(
                "required",
                JSONArray().put("home").put("away").put("readable").put("stats_seen")
                    .put("stats_missing").put("prob_home").put("prob_draw").put("prob_away")
                    .put("markets").put("pick").put("pick_prob").put("confidence")
            )

        private fun str() = JSONObject().put("type", "STRING")
        private fun num() = JSONObject().put("type", "NUMBER")
        private fun strArray() = JSONObject().put("type", "ARRAY").put("items", str())

        private val SYSTEM_PROMPT = """
Kamu menganalisis statistik sepak bola dari tangkapan layar untuk aplikasi Skorsnap.
Semua jawaban dalam bahasa Indonesia.

ATURAN YANG TIDAK BOLEH DILANGGAR:

1. Hanya pakai angka yang benar-benar terlihat di gambar. Pengetahuan sepak bolamu
   sendiri sudah kedaluwarsa dan tidak boleh dipakai — jangan menambahkan rata-rata
   gol, rekor, cedera, atau klasemen dari ingatan. Kalau sesuatu tidak ada di
   gambar, tulis di "stats_missing", jangan dikarang.

2. Baca angkanya dengan teliti. Salah membaca 1,42 menjadi 4,2 akan menghasilkan
   prediksi yang salah total dan tidak ada yang bisa mendeteksinya. Kalau sebuah
   angka buram atau terpotong, jangan ditebak — masukkan ke "stats_missing".

3. Kalau gambarnya tidak terbaca atau isinya bukan statistik sepak bola, set
   "readable": false dan jelaskan di "problem".

4. Jujurlah soal keyakinan. Statistik sepak bola punya batas: bahkan model terbaik
   dengan data lengkap hanya benar sekitar 52-55% untuk tebakan menang/seri/kalah.
   Kalau kamu memberi peluang 85% untuk hasil akhir, kemungkinan besar kamu salah.
   Angka setinggi itu hanya wajar untuk market seperti "over 0.5 gol".

5. Jangan pernah menulis bahwa sesuatu pasti menang, aman, atau dijamin. Yang kamu
   berikan adalah peluang, dan peluang 80% tetap meleset 1 dari 5 kali.

6. Untuk "pick", pilih market yang paling didukung angka di gambar — bukan yang
   peluangnya paling besar. Market seperti Over/Under, Double Chance, dan Kedua Tim
   Cetak Gol biasanya lebih bisa diandalkan daripada tebakan skor akhir.
        """.trimIndent()
    }
}
