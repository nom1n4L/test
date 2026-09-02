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
        mode: Mode = Mode.MATCH,
    ): MatchPrediction = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AnalystException("Kunci Gemini belum diisi.")
        if (images.isEmpty()) throw AnalystException("Belum ada gambar.")

        val parts = JSONArray()
        // A long capture arrives as several full-resolution bands rather than one
        // image too big to decode; see Images.forUpload.
        for (bytes in images.flatMap { Images.forUpload(it) }) {
            parts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", mimeTypeOf(bytes))
                        .put("data", Base64.getEncoder().encodeToString(bytes))
                )
            )
        }
        parts.put(JSONObject().put("text", userPrompt(note, mode)))

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
                    // Gemini thinks before it answers and those tokens come out of
                    // this same budget. At 8192 a long screenshot could spend the
                    // whole allowance reasoning and never reach the JSON, which
                    // surfaced as "jawaban terpotong" on perfectly good input.
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    // Bounded from the first attempt rather than only after a
                    // failure. Truncation used to trigger a full retry, and a retry
                    // re-uploads every screenshot — on a long capture that is the
                    // expensive half of the request, charged twice for one answer.
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", THINKING_BUDGET))
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", RESPONSE_SCHEMA)
            )

        val reply = try {
            post(model, body.toString())
        } catch (e: TruncatedException) {
            // Reasoning ate the budget. Rerun with thinking held to a fixed slice so
            // the answer itself is guaranteed room. Everything else is identical, so
            // this only ever kicks in when the normal path has already failed.
            val constrained = JSONObject(body.toString()).also { retry ->
                retry.getJSONObject("generationConfig")
                    .put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    .put("thinkingConfig", JSONObject().put("thinkingBudget", TIGHT_THINKING_BUDGET))
            }
            post(model, constrained.toString())
        }
        enforceSafePick(Grid.fill(parse(reply).copy(mode = mode)))
    }

    /**
     * Keeps the recommendation inside the band the app calls safe.
     *
     * The badge and the pick were defined separately, so a 57% market could be
     * recommended while nothing about it was marked safe — advice the rest of the
     * screen disagreed with. The instruction above asks the model to stay in the
     * band; this makes it so regardless, because a rule that matters should not
     * depend on the model choosing to follow it.
     *
     * When the pick is replaced the app says so rather than quietly presenting its
     * own choice as the model's.
     */
    internal fun enforceSafePick(p: MatchPrediction): MatchPrediction {
        val current = p.markets.firstOrNull { it.name == p.pick }
        if (current != null && current.safe) return p

        // A market the model actually looked at beats one the app worked out, even
        // when the arithmetic one reads higher: the model saw the screenshots.
        val safe = p.safePicks()
        val best = safe.firstOrNull { !it.derived } ?: safe.firstOrNull() ?: return p
        return p.copy(
            pick = best.name,
            pickProb = best.prob,
            pickCorrected = true,
        )
    }

    /** Raised when the model ran out of room before finishing its JSON. */
    private class TruncatedException :
        Exception("Jawaban model terpotong sebelum selesai.")

    /**
     * Sends the request, and follows Google's own advice when a model has retired.
     *
     * A retired model answers 404 with "no longer available to new users. Please
     * update your code to use models/gemini-3.6-flash" — the replacement is right
     * there in the message, so failing the user's analysis rather than following it
     * would be perverse. The substitution happens once and the caller is told which
     * model actually answered.
     */
    private fun post(model: String, body: String): String {
        try {
            return postOnce(model, body)
        } catch (e: RetiredModelException) {
            substituted = model to e.replacement
            return postOnce(e.replacement, body)
        }
    }

    /** What the last call actually consumed, so spending is visible rather than guessed. */
    data class Usage(val input: Int, val thinking: Int, val output: Int) {
        val total: Int get() = input + thinking + output
    }

    @Volatile
    var lastUsage: Usage? = null
        private set

    /** Set when a retired model was silently swapped for the one Google named. */
    @Volatile
    var substituted: Pair<String, String>? = null
        private set

    private class RetiredModelException(val replacement: String) : Exception()

    private fun postOnce(model: String, body: String): String {
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

            if (code !in 200..299) {
                retirementReplacement(code, text)?.let { throw RetiredModelException(it) }
                throw AnalystException(errorMessage(code, text))
            }

            val json = JSONObject(text)
            json.optJSONObject("usageMetadata")?.let {
                lastUsage = Usage(
                    input = it.optInt("promptTokenCount"),
                    thinking = it.optInt("thoughtsTokenCount"),
                    output = it.optInt("candidatesTokenCount"),
                )
            }
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
                ?: throw AnalystException(
                    json.optJSONObject("promptFeedback")?.optString("blockReason")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "Permintaan ditolak Gemini ($it)." }
                        ?: "Gemini tidak mengembalikan jawaban."
                )

            // A reply cut off mid-JSON parses as garbage; say so plainly instead.
            val finish = candidate.optString("finishReason")
            if (finish == "MAX_TOKENS") throw TruncatedException()

            val partsOut = candidate.optJSONObject("content")?.optJSONArray("parts")
                ?: throw AnalystException("Balasan Gemini kosong (alasan: ${finish.ifBlank { "tidak diketahui" }}).")

            return (0 until partsOut.length())
                .mapNotNull { partsOut.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
                .joinToString("\n")
                .trim()
        } catch (e: AnalystException) {
            throw e
        } catch (e: TruncatedException) {
            throw e
        } catch (e: Exception) {
            throw AnalystException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Explains a failure without hiding it.
     *
     * An earlier version replaced Google's own message with a guess about what had
     * gone wrong, and when the guess was incorrect — a model that lists but cannot
     * be called returns 404 for reasons the guess did not cover — the user was left
     * with advice that did not help and no way to find out more. The service's
     * message goes through verbatim; the hint is added beside it, not instead.
     */
    /**
     * The model Google tells us to use instead, or null if this is a different 404.
     */
    internal fun retirementReplacement(code: Int, body: String): String? {
        if (code != 404) return null
        // Collapsed to single spaces first: the phrase is matched as text, and a
        // line break landing mid-phrase should not decide whether the user's
        // analysis recovers or fails.
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().replace(Regex("""\s+"""), " ")
        if (!detail.contains("no longer available", true)) return null
        return Regex("""use models/([A-Za-z0-9.\-]+)""").find(detail)?.groupValues?.get(1)
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty().trim()

        val hint = when (code) {
            400 -> if (detail.contains("API key", true)) {
                "Kuncinya ditolak — salin ulang dari aistudio.google.com."
            } else {
                "Permintaan ditolak."
            }
            403 -> "Kunci tidak punya izin untuk ini."
            404 -> "Model ini tidak bisa dipanggil kuncimu, walaupun muncul di daftar. " +
                "Pilih model lain dan tekan \"Tes model ini\" di Pengaturan."
            429 -> "Kuota gratis habis untuk sekarang. Tunggu beberapa menit, atau pakai model Flash."
            in 500..599 -> "Server Gemini sedang bermasalah. Coba lagi sebentar."
            else -> "Gagal."
        }
        return if (detail.isBlank()) "$hint (HTTP $code)" else "$hint\n\nKata Google: $detail"
    }

    /**
     * A one-sentence request to the chosen model, so a broken combination shows up
     * in a second instead of after picking eight screenshots.
     */
    suspend fun testModel(model: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", "Balas persis satu kata: OK"))
                    )
                )
            )
            // Sixteen tokens used to be the whole allowance here, and current models
            // spend their first tokens thinking: every model answered MAX_TOKENS with
            // no text, which the screen reported as a bare "Gagal." So the test said
            // every model was broken while all of them worked. Two thousand tokens is
            // still a fraction of a rupiah and leaves room for an actual reply.
            .put("generationConfig", JSONObject().put("maxOutputTokens", TEST_OUTPUT_TOKENS))
        val reply = post(model, body.toString())
        val spent = lastUsage?.let { " Terpakai ${it.total} token." }.orEmpty()
        val moved = substituted?.let { " (${it.first} sudah pensiun, dialihkan ke ${it.second}.)" }.orEmpty()
        "Model $model berfungsi. Balasannya: ${reply.take(60)}$spent$moved"
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
                markets.add(
                    MarketOption(name, prob, m.optString("why"), m.optString("group").ifBlank { "Lainnya" })
                )
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

    private fun userPrompt(note: String, mode: Mode): String = buildString {
        append("Baca statistik di gambar-gambar di atas, lalu isi JSON sesuai skema.\n\n")
        if (note.isNotBlank()) append("Catatan dari pengguna: $note\n\n")
        append(if (mode == Mode.CORNER) CORNER_MARKETS else MATCH_MARKETS)
        append("\n\n")
        append(
            """
Aturan pengisian:
- prob_home + prob_draw + prob_away harus berjumlah 1,0.
- Semua "prob" adalah peluang antara 0 dan 1, bukan persen. 0,72 berarti 72%.
- Isi "markets" LENGKAP. Setiap market di daftar di atas wajib ada; satu pun jangan
  dilewati, karena daftar yang bolong bikin pengguna kehilangan pilihan pasang.
- Kalau statistik untuk satu market tipis, tetap isi dari xg_home/xg_away, turunkan
  angkanya supaya jujur, dan tulis alasannya di "why". Yang tidak boleh dikarang itu
  statistiknya, bukan hitungannya. Kalau gambarnya sendiri tidak terbaca, jangan
  menebak apa pun: set "readable" = false.
- xg_home dan xg_away wajib berisi perkiraan gol yang masuk akal dan tidak boleh 0 —
  seluruh market gol diturunkan dari keduanya.
- Setiap market wajib punya "group" persis seperti judul di daftar di atas.
- Peluang di satu pasangan harus konsisten: Over 2.5 dan Under 2.5 dijumlah 1,0,
  BTTS Ya dan Tidak dijumlah 1,0, 1X2 dijumlah 1,0.
- "pick" hanya SATU, diambil dari "markets", dan peluangnya WAJIB antara 0,68 dan
  0,92. Di bawah 0,68 terlalu dekat lempar koin untuk disebut rekomendasi; di atas
  0,92 odds-nya terlalu kecil untuk dipasang. Di dalam rentang itu, pilih yang
  peluangnya paling tinggi DAN dukungan datanya paling jelas — kalau dua market
  sama-sama didukung data, ambil yang peluangnya lebih tinggi.
- Kalau tidak ada satu pun market yang jatuh di 0,68-0,92, isi "pick" dengan yang
  paling mendekati rentang itu dan turunkan "confidence" jadi "rendah".
- "stats_seen" diisi statistik yang benar-benar kamu baca dari gambar.
- "stats_missing" diisi statistik penting yang kamu cari tapi tidak ada di gambar.
- Semua teks dalam bahasa Indonesia.
            """.trimIndent()
        )
    }

    companion object {
        private const val HOST = "https://generativelanguage.googleapis.com"

        /**
         * The alias rather than a dated name.
         *
         * `gemini-2.5-flash` was the default and it now answers 404 for any key
         * created recently — "no longer available to new users" — so a brand-new
         * paid key could not call a single model. The `-latest` aliases always
         * resolve to a current model, which is exactly the property a default needs.
         */
        const val DEFAULT_MODEL = "gemini-flash-latest"

        /**
         * Room for the answer, generous because thinking is drawn from the same
         * pot and a screenshot full of tables gives the model a lot to think about.
         */
        internal const val MAX_OUTPUT_TOKENS = 32768

        /** Thinking allowance on the retry, leaving the rest for the JSON. */
        internal const val THINKING_BUDGET = 8192

        /** The tighter budget used if a bounded first attempt still ran out of room. */
        internal const val TIGHT_THINKING_BUDGET = 2048

        /**
         * Enough room for a one-word reply after the model has finished thinking.
         */
        internal const val TEST_OUTPUT_TOKENS = 2048

        /**
         * The floor the schema puts under the market list.
         *
         * Asked for the full catalogue the model would still return three or four
         * markets and call it done, which is what made whole groups appear on one
         * match and vanish on the next. Well under the catalogue size, so a genuinely
         * thin answer is still possible — Grid fills whatever is missing.
         */
        internal const val MIN_MARKETS = 24


        /** Variants built for other jobs entirely. */
        private val SPECIALISED = listOf(
            "embedding", "-tts", "-image", "native-audio", "live-",
            "robotics", "transcribe", "guard", "computer-use", "-thinking-",
            // Listed as supporting generateContent, but every call answers
            // "This model only supports Interactions API".
            "-omni-",
        )

        /**
         * Whether a model can plausibly read a screenshot of a stats table. The
         * API also offers embedding, speech, image-generation, robotics and
         * transcription variants, and listing them all buries the three or four
         * that are actually usable.
         */
        internal fun usable(name: String): Boolean =
            name.startsWith("gemini-") && SPECIALISED.none { it in name }

        /**
         * Best first, where "best" means most likely to still work tomorrow.
         *
         * The aliases lead because they never retire; after them come the numbered
         * models newest-first, full models ahead of their lite variants, and stable
         * releases ahead of previews.
         */
        internal fun rank(models: List<Model>): List<Model> =
            models.sortedWith(compareByDescending<Model> { score(it.id) }.thenBy { it.id })

        internal fun score(id: String): Double {
            val family = when {
                "pro" in id -> 3.0
                "lite" in id -> 1.0
                else -> 2.0
            }
            val preview = if ("preview" in id || "customtools" in id) -4.0 else 0.0
            if (id.endsWith("-latest")) return 1000.0 + family
            val version = Regex("""gemini-(\d+(?:\.\d+)?)""").find(id)
                ?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return version * 10 + family + preview
        }

        /**
         * Shown only until the live list arrives. Aliases exclusively: the previous
         * list named three dated models and all three have since been closed to new
         * keys, so it sent users straight into a 404.
         */
        val MODELS = listOf(
            Triple("Gemini Flash (terbaru)", "gemini-flash-latest", "Selalu versi terkini — pilihan awal"),
            Triple("Gemini Pro (terbaru)", "gemini-pro-latest", "Paling teliti baca angka, lebih lambat"),
            Triple("Gemini Flash Lite (terbaru)", "gemini-flash-lite-latest", "Paling hemat kuota"),
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
                        JSONObject().put("type", "ARRAY").put("minItems", MIN_MARKETS).put(
                            "items",
                            JSONObject()
                                .put("type", "OBJECT")
                                .put(
                                    "properties",
                                    JSONObject().put("name", str()).put("prob", num())
                                        .put("why", str()).put("group", str())
                                )
                                .put(
                                    "required",
                                    JSONArray().put("name").put("prob").put("why").put("group")
                                )
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

        /**
         * The match-day catalogue.
         *
         * Listing the markets explicitly, with the exact group headings, is what
         * lets the screen organise forty rows without guessing where each belongs.
         * The instruction to omit rather than invent matters more here than it did
         * with eight markets: a long list is an invitation to fill it in.
         */
        internal val MATCH_MARKETS = """
Isi market-market berikut, pakai "group" persis seperti judulnya:

[Hasil Akhir]
- "Tuan rumah menang", "Seri", "Tandang menang"

[Double Chance]
- "1X (tuan rumah atau seri)", "12 (tidak seri)", "X2 (seri atau tandang)"

[Total Gol]
- "Over 0.5", "Under 0.5", "Over 1.5", "Under 1.5", "Over 2.5", "Under 2.5",
  "Over 3.5", "Under 3.5", "Over 4.5", "Under 4.5"
- "Kedua tim cetak gol (BTTS) - Ya", "Kedua tim cetak gol (BTTS) - Tidak"
- "Minimal satu tim cetak 2+ gol - Ya", "Minimal satu tim cetak 2+ gol - Tidak"

[Total Babak 1]
- "Babak 1 Over 0.5", "Babak 1 Under 0.5", "Babak 1 Over 1.5",
  "Babak 1 Under 1.5", "Babak 1 Over 2.5", "Babak 1 Under 2.5"

[Total per Tim]
- "Tuan rumah Over 0.5", "Tuan rumah Over 1.5", "Tuan rumah Over 2.5"
- "Tandang Over 0.5", "Tandang Over 1.5", "Tandang Over 2.5"

[Kombinasi Hasil + Total]
- "1X & Over 2.5", "1X & Under 2.5", "X2 & Over 2.5", "X2 & Under 2.5"
- "Tuan rumah menang & Over 1.5", "Tandang menang & Over 1.5"
- "12 & Over 2.5"

[Handicap Asia]
- "Tuan rumah -0.25", "Tandang +0.25", "Tuan rumah -0.5", "Tandang +0.5"
- "Tuan rumah -0.75", "Tandang +0.75", "Tuan rumah -1", "Tandang +1"

[Handicap Eropa]
- "Tuan rumah -1", "Tandang +1", "Tuan rumah -2", "Tandang +2"
        """.trimIndent()

        /** The corner catalogue, used when the user asks for a corner analysis. */
        internal val CORNER_MARKETS = """
Analisis ini KHUSUS SEPAK POJOK (corner). Abaikan gol; fokus ke statistik corner.
Isi market-market berikut, pakai "group" persis seperti judulnya:

[Corner]
- "Total corner Over 7.5", "Total corner Under 7.5"
- "Total corner Over 8.5", "Total corner Under 8.5"
- "Total corner Over 9.5", "Total corner Under 9.5"
- "Total corner Over 10.5", "Total corner Under 10.5"
- "Total corner Over 11.5", "Total corner Under 11.5"
- "Tuan rumah corner terbanyak", "Corner sama banyak", "Tandang corner terbanyak"

[Corner Babak 1]
- "Corner babak 1 Over 3.5", "Corner babak 1 Under 3.5"
- "Corner babak 1 Over 4.5", "Corner babak 1 Under 4.5"
- "Corner babak 1 Over 5.5", "Corner babak 1 Under 5.5"

[Corner per Tim]
- "Corner tuan rumah Over 3.5", "Corner tuan rumah Over 4.5", "Corner tuan rumah Over 5.5"
- "Corner tandang Over 3.5", "Corner tandang Over 4.5", "Corner tandang Over 5.5"

Untuk analisis corner, isi prob_home/prob_draw/prob_away dengan peluang siapa yang
mendapat corner lebih banyak, dan xg_home/xg_away dengan perkiraan jumlah corner
tiap tim.
        """.trimIndent()

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
