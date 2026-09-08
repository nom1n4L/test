package com.skorsnap.app.data

/** One turn in the post-match conversation. */
data class Turn(val fromUser: Boolean, val text: String, val at: Long = System.currentTimeMillis())

/**
 * The conversation held with the analyst after a match is settled.
 *
 * The complaint this answers: the app noted a loss, filed it, and changed nothing
 * anyone could feel — "that was wrong, next" — while the numbers stayed the same.
 * What was missing is the part a real analyst does between matches: say out loud
 * what they misread, be told they are wrong, argue about it, and come out with a
 * specific commitment rather than a shrug.
 *
 * Two things keep this from becoming theatre.
 *
 * The first is the hard rule against invention. A model asked to discuss standings,
 * injuries and rotation for a match it has no such data about will produce a
 * confident paragraph of fiction, and fiction that sounds expert is worse than
 * silence — it would end up in the record and steer later predictions. So the
 * analyst must separate what it saw from what it is inferring, and where it needs a
 * fact it does not have, it asks. That is also what makes the conversation real:
 * the questions are how the missing context actually arrives.
 *
 * The second is that the conversation has to end in something. A debrief is
 * condensed into one lesson, stored, and fed to every later analysis — so a habit
 * that keeps costing money gets named once and carried forward, instead of being
 * re-discovered every week.
 */
object Debrief {

    /** How many turns of history go back to the model, to keep a reply cheap. */
    const val CONTEXT_TURNS = 8

    /**
     * The analyst's brief.
     *
     * Written as a role rather than a list of tasks, because tone is the thing the
     * user asked for and tone does not survive being specified as bullet points. The
     * prohibitions are specific, though: those are where a persona goes wrong.
     */
    fun systemPrompt(): String = """
Kamu analis sepak bola profesional yang sedang membedah PREDIKSINYA SENDIRI yang
baru saja gagal. Bicara seperti analis betulan ke sesama orang dewasa: langsung,
tajam, tidak berputar-putar, tidak menjilat.

SIKAP
- Kalau prediksimu salah, katakan salahnya di mana dengan tegas. Jangan minta maaf
  berulang-ulang, jangan merendah palsu. Sekali akui, lalu langsung ke sebabnya.
- Kamu boleh — dan sebaiknya — mengkritik keras cara berpikirmu sendiri. "Aku terlalu
  mengandalkan rata-rata gol tanpa melihat siapa lawannya" itu kritik yang berguna.
  "Maaf ya prediksinya kurang tepat" itu tidak berguna.
- Kalau pengguna salah menyimpulkan sesuatu, katakan juga. Kamu bukan pelayan.
  Kalau dia bilang "berarti Under selalu jelek", jawab bahwa itu kesimpulan dari
  satu laga dan tunjukkan kenapa itu keliru.
- Punya pendapat. Kalau kamu masih yakin bacaanmu masuk akal meski hasilnya kalah,
  bilang begitu — laga bisa kalah tanpa analisisnya salah, dan itu justru sering.

ATURAN PALING PENTING — JANGAN MENGARANG
- Kamu HANYA tahu: statistik yang ada di ringkasan analisis di bawah, skor akhirnya,
  dan apa yang pengguna ceritakan padamu.
- Kamu TIDAK tahu klasemen, susunan pemain, cedera, kartu merah, cuaca, atau kabar
  ruang ganti — KECUALI pengguna menyebutkannya.
- Kalau butuh fakta itu untuk menjelaskan, TANYAKAN ke pengguna. Jangan dikarang.
  "Apakah ada kartu merah di babak kedua?" jauh lebih berharga daripada tebakan
  yang terdengar meyakinkan.
- Bedakan dengan jelas: "yang aku lihat di data" vs "dugaanku" vs "aku tidak tahu".
- Menyebut angka statistik yang tidak ada di ringkasan = kesalahan fatal. Jangan.

CARA MEMBEDAH
- Mulai dari sebab di hulu, bukan daftar market yang gagal. Kalau perkiraan golmu
  meleset jauh, semua market Under rontok dari SATU sebab, bukan sepuluh sebab.
- Bedakan tiga hal, dan sebutkan yang mana: (1) bacaanmu salah, (2) bacaanmu benar
  tapi memang kalah — market 80% kalah 1 dari 5 kali, itu bukan kesalahan, (3)
  datanya kurang sejak awal dan kamu tetap memberi angka yang kelewat percaya diri.
- Tutup dengan SATU hal konkret yang akan kamu ubah lain kali, atau nyatakan
  terus terang bahwa tidak ada yang perlu diubah dan kenapa. Jangan janji kabur
  seperti "akan lebih hati-hati".

BENTUK
- Bahasa Indonesia santai tapi tajam. Maksimal 200 kata per balasan.
- Jangan pakai daftar bernomor panjang. Bicara, jangan membuat laporan.
    """.trimIndent()

    /** Everything the analyst is allowed to know, laid out for it. */
    fun matchBrief(match: MatchPrediction): String = buildString {
        append("LAGA: ${match.title}")
        if (match.league.isNotBlank()) append(" (${match.league})")
        append("\n")
        if (match.result.isNotBlank()) append("HASIL: ${match.result}\n")
        append("PERKIRAAN GOL WAKTU ITU: ${twoDecimals(match.xgHome)} - ${twoDecimals(match.xgAway)}\n")
        append("PELUANG WAKTU ITU: tuan rumah ${pct(match.probHome)}, seri ")
        append("${pct(match.probDraw)}, tandang ${pct(match.probAway)}\n")
        append("REKOMENDASINYA: ${match.pick} (${match.pickPercent}%)")
        val outcome = match.pickOutcome
        append(
            when (outcome) {
                Outcome.WON -> " — TEMBUS\n"
                Outcome.LOST -> " — MELESET\n"
                Outcome.PENDING -> "\n"
            }
        )

        if (match.statsSeen.isNotEmpty()) {
            append("\nSTATISTIK YANG DIPAKAI WAKTU ITU (cuma ini yang kamu tahu):\n")
            match.statsSeen.take(14).forEach { append("- $it\n") }
        }
        if (match.statsMissing.isNotEmpty()) {
            append("\nYANG SUDAH DIAKUI TIDAK ADA WAKTU ITU:\n")
            match.statsMissing.take(8).forEach { append("- $it\n") }
        }
        if (match.risks.isNotEmpty()) {
            append("\nKERAGUAN YANG KAMU TULIS SENDIRI SEBELUM LAGA:\n")
            match.risks.forEach { append("- $it\n") }
        }
        if (match.firstRead.isNotBlank()) append("\nBACAAN AWALMU: ${match.firstRead}\n")

        val marks = match.marks()
        if (marks.isNotEmpty()) {
            val won = marks.count { it.won }
            append("\nDARI ${marks.size} MARKET YANG SUDAH DINILAI: $won tembus, ")
            append("${marks.size - won} meleset.\n")
            val painful = marks.filter { !it.won && it.promised >= 0.80 }
                .sortedByDescending { it.promised }
            if (painful.isNotEmpty()) {
                append("Yang dibilang hampir pasti tapi meleset: ")
                append(painful.take(5).joinToString { "${it.market} (${pct(it.promised)})" })
                append("\n")
            }
        }
        if (match.lesson.isNotBlank()) {
            append("\nCATATAN OTOMATIS YANG SUDAH DIHITUNG APLIKASI:\n${match.lesson}\n")
        }
    }

    /** The instruction for the opening message, before the user has said anything. */
    fun opening(): String =
        "Buka pembahasannya. Sebutkan sejujurnya apa yang salah dari bacaanmu — atau " +
            "kalau menurutmu bacaannya sebenarnya wajar dan ini cuma kalah biasa, bilang " +
            "itu dan pertahankan. Lalu tanyakan SATU hal ke pengguna yang paling bisa " +
            "mengubah kesimpulanmu, yang memang tidak mungkin kamu ketahui dari data."

    /** The instruction for condensing a finished conversation. */
    fun summaryInstruction(): String =
        "Ringkas seluruh pembahasan di atas jadi SATU pelajaran, maksimal 60 kata, untuk " +
            "dibaca olehmu sendiri sebelum menganalisis laga lain. Tulis sebagai aturan " +
            "yang bisa dipakai, bukan cerita: sebutkan kondisi apa yang harus dikenali dan " +
            "apa yang harus dilakukan berbeda. Kalau kesimpulannya justru tidak ada yang " +
            "perlu diubah, tulis itu — pelajaran palsu lebih berbahaya daripada tidak ada."

    /**
     * The turns that go back to the model.
     *
     * Trimmed to the last few, because a debrief can run long and the whole point of
     * this working at all is that a reply stays cheap. The match brief is re-sent
     * every time — it is the expensive-to-lose part — while old chat is not.
     */
    fun context(turns: List<Turn>): List<Turn> = turns.takeLast(CONTEXT_TURNS)

    private fun pct(p: Double) = "${Math.round(p * 100)}%"
}
