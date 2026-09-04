package com.skorsnap.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.skorsnap.app.MainActivity
import com.skorsnap.app.data.Analyst
import com.skorsnap.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * A floating button that records whatever app is in front.
 *
 * Tapping it once starts sampling and tapping it again stops: the user scrolls
 * through their statistics normally and the screens are collected on the way. Even
 * a tap per screen was too much friction, and it is unnecessary — the mirror runs
 * continuously, so the only real problem is deciding which frames are worth
 * keeping. See Frames for that.
 *
 * MediaProjection is Android's own API for this and the only honest way to do it:
 * the system asks the user to approve each session with a dialog the app cannot
 * suppress, and a permanent notification stays up while it runs. Nothing is read
 * that the user is not looking at, and nothing leaves the phone until they press
 * Analyse.
 */
class CaptureService : Service() {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var bubble: View? = null
    private var label: TextView? = null
    private var recording = false
    private var lastKept: IntArray? = null
    /**
     * One sampling tick: hide the button, read a frame, show it again.
     *
     * The button has to disappear for the instant the frame is read or it lands in
     * its own capture, but it must stay on screen and tappable the rest of the time
     * — it is the only way to stop the recording. Hiding it for the whole session
     * would trap the user in a recording they cannot end.
     */
    private val sampler = object : Runnable {
        override fun run() {
            if (!recording) return
            bubble?.visibility = View.INVISIBLE
            main.postDelayed({
                grab()
                bubble?.visibility = View.VISIBLE
                when {
                    !recording -> Unit
                    CaptureBus.notes.value.size + CaptureBus.shots.value.size >= Frames.MAX_FRAMES -> {
                        recording = false
                        CaptureBus.report(
                            "Sudah ${Frames.MAX_FRAMES} layar terkumpul — perekaman " +
                                "berhenti sendiri supaya tidak boros. Kembali ke Skorsnap " +
                                "dan tekan Analisis."
                        )
                        refreshLabel()
                    }
                    else -> main.postDelayed(this, SAMPLE_MS)
                }
            }, HIDE_MS)
        }
    }
    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // On Android 14 the projection cannot be obtained until the service is
        // already in the foreground, so this ordering is required, not stylistic.
        startForeground(NOTIFICATION_ID, notification())

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let {
            @Suppress("DEPRECATION")
            it.getParcelableExtra<Intent>(EXTRA_DATA)
        }
        if (data == null) {
            CaptureBus.report("Izin rekam layar tidak diberikan.")
            stopSelf()
            return START_NOT_STICKY
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, data)?.also { p ->
            // Android 14 requires a registered callback before a virtual display.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, main)
        }
        if (projection == null) {
            CaptureBus.report("Tidak bisa memulai rekam layar.")
            stopSelf()
            return START_NOT_STICKY
        }

        windows = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startMirror()
        showBubble()
        CaptureBus.setRunning(true)
        CaptureBus.report(null)
        return START_STICKY
    }

    /** A virtual display mirroring the real one into an ImageReader. */
    private fun startMirror() {
        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealMetrics(it)
        }
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "skorsnap",
            w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface, null, null,
        )
    }

    /**
     * Reads one frame out of the mirror.
     *
     * The row stride is padded to a hardware boundary, so a bitmap made at the
     * screen's width would shear. It is decoded at the padded width and then
     * cropped, which is the standard shape of this bug and invisible until it
     * happens.
     */
    private fun grab() {
        val image = reader?.acquireLatestImage()
        if (image == null) {
            CaptureBus.report("Layar belum siap, coba lagi sebentar.")
            return
        }
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val padding = rowStride - pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + padding / pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888,
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val shot = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()

            // Most sampled frames are the previous one again — a finger resting, a
            // list settling. Keeping them all would bill the user for duplicates and
            // bury the numbers among them.
            val pixels = IntArray(shot.width * shot.height)
            shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
            val signature = Frames.signature(pixels, shot.width, shot.height)
            if (recording && !Frames.changed(lastKept, signature)) {
                shot.recycle()
                return
            }
            lastKept = signature

            val out = ByteArrayOutputStream()
            shot.compress(Bitmap.CompressFormat.JPEG, 92, out)
            shot.recycle()
            read(out.toByteArray())
        } catch (e: Exception) {
            CaptureBus.report("Gagal menangkap layar: ${e.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Reads one captured screen into text and keeps the text, not the image.
     *
     * The image is discarded once transcribed. Holding twelve screens as pictures
     * and sending them all at analysis time was the expensive shape: each one is
     * billed again on every analysis it appears in, where the same page as text is
     * a few hundred tokens that can be reused and checked by eye.
     */
    private fun read(image: ByteArray) {
        val key = Store(this).apiKey
        if (key.isBlank()) {
            // No key to transcribe with, so keep the picture rather than lose the
            // screen the user just captured.
            CaptureBus.add(image)
            CaptureBus.report("Kunci Gemini belum diisi — layarnya disimpan sebagai gambar.")
            refreshLabel()
            return
        }
        CaptureBus.setReading(true)
        refreshLabel()
        scope.launch {
            val text = runCatching { Analyst(key).extract(image, Store(this@CaptureService).model) }
                .getOrElse {
                    CaptureBus.add(image)
                    CaptureBus.report("Gagal membaca layar (${it.message}) — disimpan sebagai gambar.")
                    CaptureBus.setReading(false)
                    refreshLabel()
                    return@launch
                }
            CaptureBus.setReading(false)
            // A menu, an advert or a home screen is not a statistics page; keeping
            // it would spend tokens at analysis time on nothing.
            if (text.equals("KOSONG", ignoreCase = true) || text.isBlank()) {
                CaptureBus.report("Layar itu bukan statistik — dilewati.")
            } else {
                CaptureBus.addNote(text)
                CaptureBus.report(
                    "${CaptureBus.notes.value.size} halaman terbaca. Lanjut ke halaman " +
                        "berikutnya, atau kembali ke Skorsnap dan tekan Analisis."
                )
            }
            refreshLabel()
        }
    }

    /** The draggable button, with a count so the user can see what they have. */
    private fun showBubble() {
        val text = TextView(this).apply {
            text = "📸"
            textSize = 22f
            setPadding(28, 20, 28, 20)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
        }
        label = text
        val holder = FrameLayout(this).apply { addView(text) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 400
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            toggleRecording()
        }
        holder.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    longPressed = false
                    main.postDelayed(longPress, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 12 || abs(event.rawY - downY) > 12) {
                        main.removeCallbacks(longPress)
                    }
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windows.updateViewLayout(holder, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    val moved = abs(event.rawX - downX) > 12 || abs(event.rawY - downY) > 12
                    if (!moved && !longPressed) captureOnce()
                    longPressed = false
                    true
                }
                else -> false
            }
        }

        runCatching { windows.addView(holder, params) }
            .onFailure { CaptureBus.report("Izin tampil di atas aplikasi lain belum diberikan.") }
        bubble = holder
    }

    /**
     * Starts or stops sampling.
     *
     * The button is hidden while a frame is read so it never appears in its own
     * capture, and restored immediately after.
     */
    private fun toggleRecording() {
        recording = !recording
        if (recording) {
            lastKept = null
            CaptureBus.report(
                "Merekam. Scroll pelan-pelan di aplikasi statistikmu — layar yang " +
                    "berubah diambil sendiri. Tekan tombolnya lagi kalau sudah."
            )
            main.postDelayed(sampler, 400)
        } else {
            main.removeCallbacks(sampler)
            CaptureBus.report(
                "Selesai: ${CaptureBus.shots.value.size} layar terkumpul. Kembali ke " +
                    "Skorsnap dan tekan Analisis."
            )
        }
        refreshLabel()
    }

    /** One screen, read now. The gesture the user asked for. */
    private fun captureOnce() {
        if (recording) {
            toggleRecording()
            return
        }
        bubble?.visibility = View.INVISIBLE
        main.postDelayed({
            grab()
            bubble?.visibility = View.VISIBLE
        }, HIDE_MS)
    }

    private fun refreshLabel() {
        val pages = CaptureBus.notes.value.size + CaptureBus.shots.value.size
        // Never touches visibility: the sampler owns that, and it must stay tappable.
        label?.text = when {
            CaptureBus.reading.value -> "⏳ $pages"
            recording -> "⏺ $pages"
            else -> "📷 $pages"
        }
    }

    private fun notification(): Notification {
        val channelId = "capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        "Tangkap layar",
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Skorsnap siap menangkap layar")
            .setContentText("Tekan tombol 📸 di layar untuk mengambil statistik.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(null, "Berhenti", stop).build()
            )
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        recording = false
        main.removeCallbacks(sampler)
        bubble?.let { runCatching { windows.removeView(it) } }
        bubble = null
        display?.release()
        reader?.close()
        projection?.stop()
        CaptureBus.setRunning(false)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"

        /**
         * Gap between sampled frames.
         *
         * Slow enough that a scroll settles into something readable, fast enough
         * that a normal scroll through a stats page is not missed between samples.
         */
        private const val SAMPLE_MS = 900L

        /** Long enough for the compositor to drop the button before a frame is read. */
        private const val HIDE_MS = 110L
        private const val NOTIFICATION_ID = 42
    }
}
