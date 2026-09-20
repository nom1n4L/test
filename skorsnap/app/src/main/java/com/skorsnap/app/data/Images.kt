package com.skorsnap.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import java.io.ByteArrayOutputStream

/**
 * Bitmap handling for screenshots that are far taller than a screen.
 *
 * A long capture of a stats page runs to twenty thousand pixels, and decoding one
 * costs four bytes per pixel — over eighty megabytes, against an app heap that is
 * often a hundred and twenty-eight. Decoding it whole crashes the process, which
 * is what happened, and it happened while building a ninety-six-pixel thumbnail.
 *
 * Nothing here ever holds a full-size bitmap. Previews are decoded downsampled,
 * and uploads are cut into bands one at a time, each read straight out of the
 * encoded bytes without the rest of the image being touched.
 */
object Images {

    /** Tall enough to hold a section of a stats table, short enough to stay cheap. */
    private const val SLICE_HEIGHT = 1600

    /** Bands overlap so a row of numbers is never cut in half between two slices. */
    private const val OVERLAP = 120

    /** Beyond this a screenshot is treated as a long capture and sliced. */
    private const val TALL_THRESHOLD = 2600

    /** A ceiling on request size; taller captures get proportionally taller bands. */
    private const val MAX_SLICES = 10

    private const val JPEG_QUALITY = 88

    /** Width and height without decoding a single pixel. */
    fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    /**
     * A preview small enough to draw, decoded downsampled so a huge screenshot
     * costs kilobytes rather than megabytes.
     */
    fun preview(bytes: ByteArray, maxEdge: Int = 512): Bitmap? = try {
        val (w, h) = dimensions(bytes)
        if (w <= 0 || h <= 0) {
            null
        } else {
            var sample = 1
            while (w / sample > maxEdge || h / sample > maxEdge) sample *= 2
            BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            )
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }

    /**
     * What to actually send. A normal screenshot comes back as one image; a long
     * capture comes back as several bands at full resolution.
     *
     * Full resolution is the point — the model reads these tables 768 pixels at a
     * time, so scaling a long capture down is exactly what would turn a legible
     * 1.42 into a guess. Slicing keeps every pixel while never holding more than
     * one band in memory.
     */
    fun forUpload(bytes: ByteArray): List<ByteArray> {
        val (width, height) = dimensions(bytes)
        if (width <= 0 || height <= 0) return listOf(bytes)
        if (height <= TALL_THRESHOLD) return listOf(recompressIfLarge(bytes))

        val bands = plan(height)

        val decoder = try {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        } catch (e: Exception) {
            null
        } ?: return listOf(recompressIfLarge(bytes))

        val slices = ArrayList<ByteArray>(bands.size)
        try {
            for ((top, bottom) in bands) {
                val band = try {
                    decoder.decodeRegion(Rect(0, top, width, bottom), null)
                } catch (e: OutOfMemoryError) {
                    null
                } catch (e: Exception) {
                    null
                }
                if (band != null) {
                    val out = ByteArrayOutputStream(band.byteCount / 6)
                    band.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    band.recycle()
                    slices.add(out.toByteArray())
                }
            }
        } finally {
            runCatching { decoder.recycle() }
        }
        return if (slices.isEmpty()) listOf(recompressIfLarge(bytes)) else slices
    }

    /**
     * Where to cut a tall image, as top/bottom pairs.
     *
     * Pure arithmetic, kept separate because this is where a mistake would be
     * invisible: bands that fail to overlap would slice a row of numbers in half
     * and silently lose it, and bands that fail to reach the bottom would drop the
     * end of the table without anything looking wrong.
     */
    fun plan(height: Int): List<Pair<Int, Int>> {
        if (height <= TALL_THRESHOLD) return listOf(0 to height)
        val band = maxOf(SLICE_HEIGHT, height / MAX_SLICES + OVERLAP)
        val out = ArrayList<Pair<Int, Int>>()
        var top = 0
        while (top < height) {
            val bottom = minOf(top + band, height)
            out.add(top to bottom)
            if (bottom >= height) break
            top = bottom - OVERLAP
        }
        return out
    }

    /** Shrinks the file without touching a single pixel dimension. */
    private fun recompressIfLarge(bytes: ByteArray, limit: Int = 1_200_000): ByteArray {
        if (bytes.size <= limit) return bytes
        return try {
            val (w, h) = dimensions(bytes)
            // Guard against decoding something enormous that slipped through.
            if (w.toLong() * h > 12_000_000L) return bytes
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val out = ByteArrayOutputStream(bytes.size / 4)
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            bitmap.recycle()
            val shrunk = out.toByteArray()
            if (shrunk.isNotEmpty() && shrunk.size < bytes.size) shrunk else bytes
        } catch (e: OutOfMemoryError) {
            bytes
        } catch (e: Exception) {
            bytes
        }
    }
}
