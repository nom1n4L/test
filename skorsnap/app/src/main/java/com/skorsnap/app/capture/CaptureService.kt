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
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * A floating button that photographs whatever app is in front.
 *
 * Built because the manual route — screenshot, leave the stats app, open Skorsnap,
 * find the image in the picker, repeat for each screen — is several times the work
 * of the analysis itself. This does the same thing in one tap without leaving the
 * page the numbers are on.
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
    private lateinit var windows: WindowManager
    private val main = Handler(Looper.getMainLooper())

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

            val out = ByteArrayOutputStream()
            shot.compress(Bitmap.CompressFormat.JPEG, 92, out)
            shot.recycle()
            CaptureBus.add(out.toByteArray())
            CaptureBus.report(null)
        } catch (e: Exception) {
            CaptureBus.report("Gagal menangkap layar: ${e.message}")
        } finally {
            image.close()
        }
    }

    /** The draggable button, with a count so the user can see what they have. */
    private fun showBubble() {
        val label = TextView(this).apply {
            text = "📸"
            textSize = 22f
            setPadding(28, 20, 28, 20)
            setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
        }
        val holder = FrameLayout(this).apply { addView(label) }

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
        holder.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (event.rawX - downX).toInt()
                    params.y = startY + (event.rawY - downY).toInt()
                    windows.updateViewLayout(holder, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(event.rawX - downX) > 12 || abs(event.rawY - downY) > 12
                    if (!moved) {
                        // Hidden for the frame so the button is not in its own
                        // screenshot, then restored once the pixels are read.
                        holder.visibility = View.INVISIBLE
                        main.postDelayed({
                            grab()
                            holder.visibility = View.VISIBLE
                            label.text = "📸 ${CaptureBus.shots.value.size}"
                        }, 120)
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { windows.addView(holder, params) }
            .onFailure { CaptureBus.report("Izin tampil di atas aplikasi lain belum diberikan.") }
        bubble = holder
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
        private const val NOTIFICATION_ID = 42
    }
}
