package com.skorsnap.app.capture

import kotlin.math.abs

/**
 * Decides which frames of a scroll are worth keeping.
 *
 * Sampling a screen every second while the user scrolls produces mostly the same
 * picture over and over: a finger resting, a list settling, a moment between
 * flicks. Sending all of it would cost tokens for nothing and bury the numbers in
 * duplicates. This keeps a frame only when enough of the screen has actually
 * changed since the last one kept.
 *
 * The signature is a coarse grid of brightness values. It is deliberately crude —
 * it should notice a list scrolling by a few rows and ignore a blinking cursor or
 * a clock ticking over.
 */
object Frames {

    /** Grid resolution of the signature. Small on purpose: this is not a thumbnail. */
    const val GRID = 16

    /**
     * How much of the grid must differ before a frame counts as new.
     *
     * Tuned by what the two failure modes cost. Too low and a scroll produces near
     * duplicates that waste tokens; too high and a page that changed only in its
     * lower half is dropped, losing numbers the user meant to capture. Missing data
     * is the worse of the two, so this leans towards keeping.
     */
    const val CHANGE_THRESHOLD = 0.06

    /**
     * Ceiling on frames kept in one recording.
     *
     * Every frame is billed. Ten screens of statistics is already more than a match
     * analysis needs, and without a cap a phone left recording would quietly spend
     * the user's whole daily allowance.
     */
    const val MAX_FRAMES = 12

    /**
     * True when the two signatures differ enough that the second is worth keeping.
     *
     * A null previous signature means nothing has been kept yet, so the frame is
     * always taken.
     */
    fun changed(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null) return true
        if (previous.size != current.size || current.isEmpty()) return true
        var difference = 0L
        for (i in current.indices) difference += abs(previous[i] - current[i])
        val average = difference.toDouble() / current.size / 255.0
        return average >= CHANGE_THRESHOLD
    }

    /**
     * Reduces a frame to its brightness grid.
     *
     * Takes the pixels already in memory rather than a Bitmap so it can be tested
     * without a device: the caller reads the buffer once and passes it here.
     */
    fun signature(pixels: IntArray, width: Int, height: Int): IntArray {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return IntArray(0)
        val out = IntArray(GRID * GRID)
        val cellW = width / GRID
        val cellH = height / GRID
        if (cellW == 0 || cellH == 0) return IntArray(0)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                var sum = 0L
                var count = 0
                // A few samples per cell is plenty and keeps this cheap enough to
                // run on every sampled frame.
                var y = gy * cellH
                while (y < (gy + 1) * cellH) {
                    var x = gx * cellW
                    while (x < (gx + 1) * cellW) {
                        val p = pixels[y * width + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        sum += (r * 299 + g * 587 + b * 114) / 1000
                        count++
                        x += 8
                    }
                    y += 8
                }
                out[gy * GRID + gx] = if (count == 0) 0 else (sum / count).toInt()
            }
        }
        return out
    }
}
