package com.fedemeju.colortap

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager

/** Servicio en primer plano que captura la pantalla y pasa cada frame a Engine. */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "capture"

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CaptureService::class.java).setAction(ACTION_STOP))
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var curW = 0
    private var curH = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { handler.post { resizeIfNeeded() } }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Engine.load(this)
        thread = HandlerThread("capture").apply { start() }
        handler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        startAsForeground()

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        val data: Intent? = readData(intent)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val proj = mpm.getMediaProjection(code, data) ?: run { stopSelf(); return START_NOT_STICKY }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val (w, h, dpi) = screenSize()
        curW = w; curH = h
        val r = newReader(w, h)
        reader = r
        display = proj.createVirtualDisplay(
            "colortap", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler
        )
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, handler)

        Engine.capturing = true
        Engine.stateChanged(this)
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(intent: Intent?): Intent? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else intent.getParcelableExtra<Intent>(EXTRA_DATA)
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ rd ->
            val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = img.planes[0]
                Engine.onFrame(this, plane.buffer, img.width, img.height, plane.rowStride, plane.pixelStride)
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }, handler)
        return r
    }

    /** Si el celular gira (juego horizontal), ajusta la captura al nuevo tamaño. */
    private fun resizeIfNeeded() {
        val d = display ?: return
        val (w, h, dpi) = screenSize()
        if (w == curW && h == curH) return
        curW = w; curH = h
        val old = reader
        val r = newReader(w, h)
        reader = r
        d.resize(w, h, dpi)
        d.surface = r.surface
        old?.close()
    }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Captura de pantalla", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ColorTap listo")
            .setContentText("Tocá la burbuja para activar o pausar")
            .setContentIntent(openPi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_tile), "Detener", stopPi).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        Engine.capturing = false
        Engine.active = false
        Engine.editing = false
        try { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        display = null; reader = null; projection = null
        thread.quitSafely()
        Engine.stateChanged(this)
        super.onDestroy()
    }
}
