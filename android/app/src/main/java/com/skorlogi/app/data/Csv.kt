package com.skorlogi.app.data

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * A small CSV reader. The feeds are plain comma-separated files whose team names
 * occasionally contain quoted commas, so quoting has to be handled, but nothing
 * more exotic than that appears in them.
 */
object Csv {

    fun splitLine(line: String): List<String> {
        val out = ArrayList<String>(32)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString().trim()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString().trim())
        return out
    }

    /** Parses a CSV body into row maps keyed by header name. Blank rows are dropped. */
    fun parse(body: String): List<Map<String, String>> {
        val lines = body.lineSequence().iterator()
        if (!lines.hasNext()) return emptyList()
        val header = splitLine(lines.next().removePrefix("﻿")).map { it.trim() }
        val rows = ArrayList<Map<String, String>>(512)
        while (lines.hasNext()) {
            val raw = lines.next()
            if (raw.isBlank()) continue
            val cells = splitLine(raw)
            if (cells.all { it.isEmpty() }) continue
            val map = HashMap<String, String>(header.size * 2)
            for (i in header.indices) {
                val name = header[i]
                if (name.isEmpty()) continue
                map[name] = cells.getOrElse(i) { "" }
            }
            rows.add(map)
        }
        return rows
    }
}

/**
 * Thrown when a download fails in a way worth showing the user.
 *
 * @param unreachable true when the host could not be reached at all, as opposed to
 *   answering with an error. The distinction matters: a network that blocks a
 *   domain fails every request against it, so there is no point grinding through
 *   eighty more downloads, and the user needs to be told something quite
 *   different from "file not found".
 */
class FetchException(
    message: String,
    cause: Throwable? = null,
    val unreachable: Boolean = false,
) : Exception(message, cause)

object Http {
    private const val TIMEOUT_MS = 25_000

    fun getText(url: String): String {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty("User-Agent", "Skorlogi/1.0 (Android)")
            }
            val code = conn.responseCode
            if (code == 404) throw FetchException("404")
            if (code !in 200..299) throw FetchException("HTTP $code")

            val raw = conn.inputStream
            val stream = if (conn.contentEncoding?.contains("gzip", true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
        } catch (e: FetchException) {
            throw e
        } catch (e: Exception) {
            val unreachable = e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.NoRouteToHostException ||
                e is java.net.SocketTimeoutException ||
                e is javax.net.ssl.SSLException
            throw FetchException(e.message ?: e.javaClass.simpleName, e, unreachable)
        } finally {
            conn?.disconnect()
        }
    }
}
