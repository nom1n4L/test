package com.skorlogi.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One bookmaker's price for one selection. */
data class Price(val bookmaker: String, val bookmakerKey: String, val price: Double)

/**
 * Every bookmaker's price for one selection, e.g. "Over" on the 2.5 goals line.
 * `point` is the line itself where the market has one.
 */
data class Selection(
    val market: String,
    val name: String,
    val point: Double?,
    val prices: List<Price>,
) {
    val label: String get() = if (point == null) name else "$name ${fmt(point)}"

    /** The most a bookmaker will pay for this. Line shopping in one line of code. */
    val best: Price? get() = prices.maxByOrNull { it.price }

    fun priceFrom(bookmakerKey: String): Double? =
        prices.firstOrNull { it.bookmakerKey == bookmakerKey }?.price

    private fun fmt(v: Double) = if (v == v.toLong().toDouble()) "${v.toLong()}" else "$v"
}

data class OddsEvent(
    val id: String,
    val sportKey: String,
    val commenceEpochDay: Long,
    val commenceTime: String,
    val home: String,
    val away: String,
    val selections: List<Selection>,
) {
    /** Selections belonging to one market, e.g. every over/under line. */
    fun market(key: String): List<Selection> = selections.filter { it.market == key }

    val bookmakerCount: Int
        get() = selections.flatMap { it.prices }.map { it.bookmakerKey }.distinct().size
}

data class OddsQuota(val remaining: Int, val used: Int, val lastCost: Int)

data class SportKey(val key: String, val title: String, val group: String, val active: Boolean)

/**
 * Client for the-odds-api.com, which aggregates prices from many bookmakers.
 *
 * This exists because of a measurement rather than a hunch. Against 2,770 held-out
 * matches the app's own model lost to the bookmaker's price at every market tested
 * — so the useful question stopped being "can we predict better" and became "can
 * we get a better price for the same bet". Two things follow from having many
 * books at once, and neither needs a forecast: taking the highest price on offer,
 * and noticing when a soft book disagrees with a sharp one.
 *
 * Quota is the binding constraint on a free key — 500 credits a month, and one
 * request costs markets × regions — so requests here ask for whole competitions
 * at a time and the caller caches hard.
 */
object OddsApi {

    private const val HOST = "https://api.the-odds-api.com/v4"
    private const val TIMEOUT_MS = 25_000

    /**
     * Pinnacle takes the sharpest position of any public book and runs a much
     * thinner margin, so its price is the closest thing to a true probability that
     * can be looked up. It is the reference the value check measures against.
     */
    const val SHARP_BOOKMAKER = "pinnacle"

    val MARKET_LABELS = linkedMapOf(
        "h2h" to "Hasil Akhir",
        "totals" to "Total Gol",
        "spreads" to "Handicap",
        "btts" to "Kedua Tim Cetak Gol",
        "draw_no_bet" to "Draw No Bet",
        "team_totals" to "Total Gol per Tim",
    )

    class OddsException(message: String) : Exception(message)

    @Volatile
    var lastQuota: OddsQuota? = null
        private set

    private fun get(key: String, path: String, query: String): String {
        if (key.isBlank()) throw OddsException("Kunci the-odds-api belum diisi.")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$HOST/$path?apiKey=$key&$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()

            // The service reports quota in headers on every call, including failures.
            conn.getHeaderField("x-requests-remaining")?.toIntOrNull()?.let { remaining ->
                lastQuota = OddsQuota(
                    remaining = remaining,
                    used = conn.getHeaderField("x-requests-used")?.toIntOrNull() ?: 0,
                    lastCost = conn.getHeaderField("x-requests-last")?.toIntOrNull() ?: 0,
                )
            }

            when (code) {
                401 -> throw OddsException("Kunci ditolak. Periksa lagi kunci dari the-odds-api.com.")
                422 -> throw OddsException("Permintaan ditolak: market atau liga itu tidak tersedia.")
                429 -> throw OddsException("Kuota bulanan habis.")
            }
            if (code !in 200..299) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull()
                throw OddsException(message?.takeIf { it.isNotBlank() } ?: "Server menolak (HTTP $code)")
            }
            return body
        } catch (e: OddsException) {
            throw e
        } catch (e: Exception) {
            throw OddsException(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** Competitions the key can see. This call is free — it costs no credits. */
    fun sports(key: String): List<SportKey> {
        val arr = runCatching { JSONArray(get(key, "sports/", "all=false")) }.getOrNull()
            ?: throw OddsException("Balasan tidak dikenali.")
        val out = ArrayList<SportKey>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                SportKey(
                    key = o.optString("key"),
                    title = o.optString("title"),
                    group = o.optString("group"),
                    active = o.optBoolean("active", true),
                )
            )
        }
        return out
    }

    /**
     * Prices for a whole competition.
     *
     * @param markets which markets to ask for. Each one multiplies the credit cost,
     *   so the caller should ask only for what it will show.
     */
    fun odds(
        key: String,
        sportKey: String,
        markets: List<String> = listOf("h2h", "totals"),
        regions: String = "eu",
    ): List<OddsEvent> {
        val body = get(
            key,
            "sports/$sportKey/odds/",
            "regions=$regions&markets=${markets.joinToString(",")}&oddsFormat=decimal",
        )
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = ArrayList<OddsEvent>(arr.length())

        for (i in 0 until arr.length()) {
            val event = arr.optJSONObject(i) ?: continue
            val commence = event.optString("commence_time")
            val day = parseIsoDate(commence) ?: continue

            // Gather every book's price under one selection each, so the best of
            // them is a single max rather than a search across the tree.
            val grouped = LinkedHashMap<String, MutableList<Price>>()
            val meta = HashMap<String, Triple<String, String, Double?>>()

            val books = event.optJSONArray("bookmakers") ?: JSONArray()
            for (b in 0 until books.length()) {
                val book = books.optJSONObject(b) ?: continue
                val bookKey = book.optString("key")
                val bookTitle = book.optString("title").ifBlank { bookKey }
                val marketArr = book.optJSONArray("markets") ?: continue
                for (m in 0 until marketArr.length()) {
                    val market = marketArr.optJSONObject(m) ?: continue
                    val marketKey = market.optString("key")
                    val outcomes = market.optJSONArray("outcomes") ?: continue
                    for (o in 0 until outcomes.length()) {
                        val outcome = outcomes.optJSONObject(o) ?: continue
                        val name = outcome.optString("name")
                        val price = outcome.optDouble("price", 0.0)
                        if (name.isEmpty() || price <= 1.0) continue
                        val point = if (outcome.isNull("point")) null else outcome.optDouble("point")
                        val id = "$marketKey|$name|${point ?: ""}"
                        grouped.getOrPut(id) { ArrayList() }.add(Price(bookTitle, bookKey, price))
                        meta[id] = Triple(marketKey, name, point)
                    }
                }
            }

            val selections = grouped.mapNotNull { (id, prices) ->
                val (marketKey, name, point) = meta[id] ?: return@mapNotNull null
                Selection(marketKey, name, point, prices.sortedByDescending { it.price })
            }

            out.add(
                OddsEvent(
                    id = event.optString("id"),
                    sportKey = event.optString("sport_key"),
                    commenceEpochDay = day,
                    commenceTime = commence.substringAfter('T').take(5),
                    home = event.optString("home_team"),
                    away = event.optString("away_team"),
                    selections = selections,
                )
            )
        }
        return out
    }

    private fun parseIsoDate(iso: String?): Long? {
        if (iso == null || iso.length < 10) return null
        val y = iso.substring(0, 4).toIntOrNull() ?: return null
        val m = iso.substring(5, 7).toIntOrNull() ?: return null
        val d = iso.substring(8, 10).toIntOrNull() ?: return null
        return Dates.toEpochDay(y, m, d)
    }
}
