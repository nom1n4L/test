package com.skorsnap.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where screen captures land between the service that takes them and the app that
 * analyses them.
 *
 * A plain object rather than a bound service or a broadcast: the capture button
 * lives in a foreground service and the screen that consumes the images lives in
 * the activity, and those two need to share a list of byte arrays and nothing
 * else. Anything more elaborate would be machinery around a single variable.
 *
 * The bytes are JPEGs, the same shape the picker produces, so everything
 * downstream — the band splitting, the analysis, the token accounting — is
 * unchanged and untested code paths are not introduced by this feature.
 */
object CaptureBus {

    private val _shots = MutableStateFlow<List<ByteArray>>(emptyList())
    val shots: StateFlow<List<ByteArray>> = _shots.asStateFlow()

    /**
     * Screens already read into text, one entry per capture.
     *
     * Held as text rather than as images because an image is billed again on every
     * analysis that includes it. Read once, the same page costs a few hundred
     * tokens instead of tens of thousands, and the user can see what was read
     * before anything is predicted from it.
     */
    private val _notes = MutableStateFlow<List<String>>(emptyList())
    val notes: StateFlow<List<String>> = _notes.asStateFlow()

    /** True while a capture is being transcribed, so the button can show it. */
    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    fun addNote(text: String) {
        _notes.value = _notes.value + text
    }

    fun setReading(value: Boolean) {
        _reading.value = value
    }

    fun clearNotes() {
        _notes.value = emptyList()
    }

    fun dropNote(index: Int) {
        _notes.value = _notes.value.filterIndexed { i, _ -> i != index }
    }

    /** True while the floating button is on screen, so the app can show its state. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Set when the service has something to say the user should see in the app. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem.asStateFlow()

    fun add(shot: ByteArray) {
        _shots.value = _shots.value + shot
    }

    fun take(): List<ByteArray> {
        val out = _shots.value
        _shots.value = emptyList()
        return out
    }

    fun clear() {
        _shots.value = emptyList()
    }

    fun setRunning(value: Boolean) {
        _running.value = value
    }

    fun report(message: String?) {
        _problem.value = message
    }
}
