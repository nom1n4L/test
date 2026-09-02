package com.skorsnap.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps analysed matches between sessions, so a slip survives closing the app and
 * a screenshot only has to be read once. Small enough for preferences; a database
 * would be machinery for its own sake.
 */
class Store(context: Context) {

    private val prefs = context.getSharedPreferences("skorsnap", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    val hasKey: Boolean get() = apiKey.isNotBlank()

    var model: String
        get() = prefs.getString("model", Analyst.DEFAULT_MODEL).orEmpty()
            .ifBlank { Analyst.DEFAULT_MODEL }
        set(v) = prefs.edit().putString("model", v).apply()

    fun load(): List<MatchPrediction> {
        val raw = prefs.getString("matches", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::fromJson) }
        }.getOrDefault(emptyList())
    }

    fun save(matches: List<MatchPrediction>) {
        val arr = JSONArray()
        matches.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("matches", arr.toString()).apply()
    }

    private fun toJson(m: MatchPrediction) = JSONObject().apply {
        put("id", m.id)
        put("home", m.home)
        put("away", m.away)
        put("league", m.league)
        put("readable", m.readable)
        put("problem", m.problem)
        put("stats_seen", JSONArray(m.statsSeen))
        put("stats_missing", JSONArray(m.statsMissing))
        put("prob_home", m.probHome)
        put("prob_draw", m.probDraw)
        put("prob_away", m.probAway)
        put("xg_home", m.xgHome)
        put("xg_away", m.xgAway)
        put("pick", m.pick)
        put("pick_prob", m.pickProb)
        put("confidence", m.confidence)
        put("confidence_why", m.confidenceWhy)
        put(
            "market_outcomes",
            JSONObject().apply { m.marketOutcomes.forEach { (k, v) -> put(k, v.name) } }
        )
        put("mode", m.mode.name)
        put("backed", m.backed)
        put("model", m.model)
        put("pick_corrected", m.pickCorrected)
        put(
            "markets",
            JSONArray().apply {
                m.markets.forEach {
                    put(
                        JSONObject().put("name", it.name).put("prob", it.prob)
                            .put("why", it.why).put("group", it.group)
                            .put("derived", it.derived)
                    )
                }
            }
        )
    }

    private fun fromJson(o: JSONObject): MatchPrediction {
        fun strings(key: String): List<String> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }
        val markets = ArrayList<MarketOption>()
        o.optJSONArray("markets")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                markets.add(
                    MarketOption(
                        m.optString("name"),
                        m.optDouble("prob", 0.0),
                        m.optString("why"),
                        m.optString("group").ifBlank { "Lainnya" },
                        m.optBoolean("derived", false),
                    )
                )
            }
        }
        return MatchPrediction(
            id = o.optString("id"),
            home = o.optString("home"),
            away = o.optString("away"),
            league = o.optString("league"),
            readable = o.optBoolean("readable", true),
            problem = o.optString("problem"),
            statsSeen = strings("stats_seen"),
            statsMissing = strings("stats_missing"),
            probHome = o.optDouble("prob_home", 0.0),
            probDraw = o.optDouble("prob_draw", 0.0),
            probAway = o.optDouble("prob_away", 0.0),
            xgHome = o.optDouble("xg_home", 0.0),
            xgAway = o.optDouble("xg_away", 0.0),
            markets = markets,
            pick = o.optString("pick"),
            pickProb = o.optDouble("pick_prob", 0.0),
            confidence = o.optString("confidence", "sedang"),
            confidenceWhy = o.optString("confidence_why"),
            marketOutcomes = Migration.marketOutcomes(o, markets),
            mode = runCatching { Mode.valueOf(o.optString("mode")) }.getOrDefault(Mode.MATCH),
            backed = o.optString("backed"),
            model = o.optString("model"),
            pickCorrected = o.optBoolean("pick_corrected"),
        )
    }
}

/** Reading saves written before a field existed. */
object Migration {

    /**
     * The per-market results, rebuilt from whichever shape the save was written in.
     *
     * Three generations exist on real devices: a single `outcome`, then separate
     * `pick_outcome`/`backed_outcome`, and now a map. Each older verdict is filed
     * under the market it was actually about, so nothing is lost and nothing is
     * attributed to a market that was never judged.
     */
    fun marketOutcomes(o: JSONObject, markets: List<MarketOption>): Map<String, Outcome> {
        o.optJSONObject("market_outcomes")?.let { saved ->
            val out = HashMap<String, Outcome>()
            saved.keys().forEach { key ->
                runCatching { Outcome.valueOf(saved.optString(key)) }
                    .getOrNull()
                    ?.takeIf { it != Outcome.PENDING }
                    ?.let { out[key] = it }
            }
            return out
        }

        fun keyFor(name: String): String =
            markets.firstOrNull { it.name == name }?.let { "${it.group}|${it.name}" } ?: "|$name"

        val (pick, backed) = outcomes(o)
        val out = HashMap<String, Outcome>()
        val pickName = o.optString("pick")
        val backedName = o.optString("backed").ifBlank { pickName }
        if (pick != Outcome.PENDING && pickName.isNotBlank()) out[keyFor(pickName)] = pick
        if (backed != Outcome.PENDING && backedName.isNotBlank()) out[keyFor(backedName)] = backed
        return out
    }

    /**
     * The two records, reading older saves that only ever had one.
     *
     * The single old flag described whichever market was actually backed. So when
     * the user had backed something other than the recommendation, that verdict
     * belongs to their bet and the recommendation was never judged — recording it
     * as the app's own result too would credit the app with a call it never made.
     * When nothing was backed, the two are the same market and share the verdict.
     */
    fun outcomes(o: JSONObject): Pair<Outcome, Outcome> {
        val fresh = o.has("pick_outcome") || o.has("backed_outcome")
        if (fresh) {
            fun read(key: String) = runCatching { Outcome.valueOf(o.optString(key)) }
                .getOrDefault(Outcome.PENDING)
            return read("pick_outcome") to read("backed_outcome")
        }
        val old = runCatching { Outcome.valueOf(o.optString("outcome")) }
            .getOrDefault(Outcome.PENDING)
        val backed = o.optString("backed")
        val divergent = backed.isNotBlank() && backed != o.optString("pick")
        return if (divergent) Outcome.PENDING to old else old to old
    }
}
