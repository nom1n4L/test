package com.skorsnap.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.skorsnap.app.capture.CaptureBus
import com.skorsnap.app.capture.CaptureService
import com.skorsnap.app.ui.App
import com.skorsnap.app.ui.AppViewModel
import com.skorsnap.app.ui.SkorsnapTheme

class MainActivity : ComponentActivity() {

    private val viewModel: AppViewModel by viewModels()

    /**
     * The system's own screen-capture consent dialog.
     *
     * It cannot be skipped or pre-answered, which is the point: the app never gets
     * to see the screen without the user agreeing to it in a dialog Android draws
     * itself, every session.
     */
    private val consent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            CaptureBus.report("Izin rekam layar ditolak.")
            return@registerForActivityResult
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODE, result.resultCode)
                .putExtra(CaptureService.EXTRA_DATA, data),
        )
    }

    private val overlay = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { askForScreen() }

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startCapture() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SkorsnapTheme {
                App(
                    vm = viewModel,
                    onStartCapture = { startCapture() },
                    onStopCapture = {
                        startService(
                            Intent(this, CaptureService::class.java)
                                .setAction(CaptureService.ACTION_STOP)
                        )
                    },
                )
            }
        }
    }

    /**
     * Walks the three consents in the order Android requires them.
     *
     * Notifications first because the capture runs as a foreground service and its
     * notification is what makes the capture visible while it is happening; then
     * the overlay permission for the floating button, which is a separate settings
     * screen rather than a dialog; then the capture itself.
     */
    private fun startCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            CaptureBus.report(
                "Butuh izin \"tampil di atas aplikasi lain\" untuk tombol melayangnya. " +
                    "Nyalakan di layar yang terbuka, lalu kembali ke sini."
            )
            overlay.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        askForScreen()
    }

    private fun askForScreen() {
        if (!Settings.canDrawOverlays(this)) {
            CaptureBus.report("Izin tampil di atas aplikasi lain masih belum aktif.")
            return
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consent.launch(manager.createScreenCaptureIntent())
    }
}
