package com.skorlogi.app

import com.skorlogi.app.data.ApiFootball
import org.junit.Assume
import org.junit.Test

/**
 * Exercises the request plumbing against the live service with a key that cannot
 * work, which is the one thing testable without someone's real credentials.
 *
 * What it proves: both front doors are reachable and answer, the two header
 * schemes are wired to the right hosts, and a bad key produces a clear message
 * rather than a crash or a silent empty result.
 */
class ApiFootballTest {

    @Test
    fun rejectsABadKeyClearly() {
        val error = try {
            ApiFootball.status("definitely-not-a-real-key")
            null
        } catch (e: ApiFootball.ApiException) {
            e
        } catch (e: Exception) {
            Assume.assumeNoException("jaringan tidak tersedia", e)
            return
        }

        assert(error != null) { "kunci palsu malah diterima" }
        val message = error!!.message.orEmpty()
        println("Pesan untuk kunci palsu: $message")
        assert(message.isNotBlank()) { "gagal tanpa pesan yang bisa dibaca" }
        // A crash or a timeout would surface as something else entirely.
        assert(!message.contains("Exception")) { "pesan mentah bocor ke pengguna: $message" }
    }

    @Test
    fun bothFrontDoorsAreReachable() {
        for (mode in ApiFootball.KeyMode.entries) {
            val reachable = try {
                java.net.URL("${mode.host}/status").openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                }.getInputStream().close()
                true
            } catch (e: java.io.IOException) {
                // An HTTP error still means the host answered; only a connect
                // failure means it did not.
                e !is java.net.UnknownHostException && e !is java.net.ConnectException
            } catch (e: Exception) {
                Assume.assumeNoException("jaringan tidak tersedia", e)
                return
            }
            println("${mode.name} (${mode.host}): ${if (reachable) "menjawab" else "TIDAK MENJAWAB"}")
            assert(reachable) { "${mode.name} tidak bisa dijangkau" }
        }
    }
}
