package com.skorlogi.app.data

import android.content.SharedPreferences
import com.skorlogi.app.engine.Pick
import com.skorlogi.app.engine.PickKind
import org.json.JSONArray
import org.json.JSONObject

enum class Outcome { PENDING, WON, LOST, VOID }

data class TrackedPick(
    val id: String,
    val league: String,
    val dateEpochDay: Long,
    val home: String,
    val away: String,
    val market: String,
    val selection: String,
    val kind: PickKind,
    val prob: Double,
    val odds: Double,
    val outcome: Outcome,
)

/**
 * What the user actually followed, and how it turned out.
 *
 * The point of this is the one number a person cannot get from memory: how the
 * picks did against what they promised. Remembering the winners and forgetting the
 * rest is the normal way to read one's own betting history, and a model that
 * claims 75% is only trustworthy if 75% is roughly what lands.
 *
 * Results are settled automatically from the match history the app already keeps,
 * so nothing has to be entered by hand except the odds, and those are optional.
 */
class Tracker(private val prefs: SharedPreferences) {

    fun all(): List<TrackedPick> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { o ->
                    TrackedPick(
                        id = o.optString("id"),
                        league = o.optString("league"),
                        dateEpochDay = o.optLong("date"),
                        home = o.optString("home"),
                        away = o.optString("away"),
                        market = o.optString("market"),
                        selection = o.optString("selection"),
                        kind = runCatching { PickKind.valueOf(o.optString("kind")) }.getOrNull()
                            ?: return@let null,
                        prob = o.optDouble("prob", 0.0),
                        odds = o.optDouble("odds", 0.0),
                        outcome = runCatching { Outcome.valueOf(o.optString("outcome")) }
                            .getOrDefault(Outcome.PENDING),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun save(list: List<TrackedPick>) {
        val arr = JSONArray()
        for (t in list) {
            arr.put(
                JSONObject()
                    .put("id", t.id)
                    .put("league", t.league)
                    .put("date", t.dateEpochDay)
                    .put("home", t.home)
                    .put("away", t.away)
                    .put("market", t.market)
                    .put("selection", t.selection)
                    .put("kind", t.kind.name)
                    .put("prob", t.prob)
                    .put("odds", t.odds)
                    .put("outcome", t.outcome.name)
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    fun isFollowed(pick: Pick): Boolean = all().any { it.id == idOf(pick) }

    fun follow(pick: Pick, odds: Double = 0.0) {
        val list = all().toMutableList()
        val id = idOf(pick)
        if (list.any { it.id == id }) return
        list.add(
            TrackedPick(
                id = id,
                league = pick.fixture.league,
                dateEpochDay = pick.fixture.dateEpochDay,
                home = pick.fixture.home,
                away = pick.fixture.away,
                market = pick.market,
                selection = pick.selection,
                kind = pick.kind,
                prob = pick.prob,
                odds = odds,
                outcome = Outcome.PENDING,
            )
        )
        save(list)
    }

    fun unfollow(id: String) = save(all().filterNot { it.id == id })

    fun setOdds(id: String, odds: Double) =
        save(all().map { if (it.id == id) it.copy(odds = odds) else it })

    fun clearSettled() = save(all().filter { it.outcome == Outcome.PENDING })

    /**
     * Resolves pending picks against finished matches. Kick-off dates shift, so a
     * match is matched on the teams within a couple of days of the expected date.
     */
    fun settle(matches: List<Match>) {
        val pending = all()
        if (pending.none { it.outcome == Outcome.PENDING }) return

        val index = HashMap<String, MutableList<Match>>()
        for (m in matches) {
            index.getOrPut("${m.league}|${m.home}|${m.away}") { ArrayList() }.add(m)
        }

        var changed = false
        val updated = pending.map { t ->
            if (t.outcome != Outcome.PENDING) return@map t
            val candidates = index["${t.league}|${t.home}|${t.away}"] ?: return@map t
            val match = candidates.minByOrNull { kotlin.math.abs(it.dateEpochDay - t.dateEpochDay) }
                ?.takeIf { kotlin.math.abs(it.dateEpochDay - t.dateEpochDay) <= 2 }
                ?: return@map t
            val won = t.kind.settle(
                match.homeGoals, match.awayGoals, match.htHomeGoals, match.htAwayGoals,
            ) ?: return@map t
            changed = true
            t.copy(outcome = if (won) Outcome.WON else Outcome.LOST)
        }
        if (changed) save(updated)
    }

    fun stats(): TrackerStats {
        val list = all()
        val settled = list.filter { it.outcome == Outcome.WON || it.outcome == Outcome.LOST }
        val won = settled.count { it.outcome == Outcome.WON }
        val withOdds = settled.filter { it.odds > 1.0 }
        val returned = withOdds.sumOf { if (it.outcome == Outcome.WON) it.odds else 0.0 }
        return TrackerStats(
            total = list.size,
            pending = list.count { it.outcome == Outcome.PENDING },
            settled = settled.size,
            won = won,
            expected = if (settled.isEmpty()) 0.0 else settled.sumOf { it.prob } / settled.size,
            staked = withOdds.size.toDouble(),
            returned = returned,
        )
    }

    private fun idOf(pick: Pick) = "${pick.fixture.key}|${pick.kind.name}"

    private companion object {
        const val KEY = "tracked_picks"
    }
}

data class TrackerStats(
    val total: Int,
    val pending: Int,
    val settled: Int,
    val won: Int,
    val expected: Double,
    val staked: Double,
    val returned: Double,
) {
    val hitRate: Double get() = if (settled == 0) 0.0 else won.toDouble() / settled

    /** Positive means the picks beat what they promised; negative means they missed. */
    val vsExpected: Double get() = hitRate - expected

    val hasMoney: Boolean get() = staked > 0
    val profit: Double get() = returned - staked
    val roi: Double get() = if (staked <= 0) 0.0 else profit / staked
}
