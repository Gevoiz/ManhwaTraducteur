package com.manhwatraducteur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * Miroir permanent de l'écran via MediaProjection. On garde toujours la dernière image reçue
 * pour pouvoir la convertir en Bitmap instantanément quand l'utilisateur demande une traduction.
 * (Depuis Android 14, une MediaProjection ne peut créer qu'un seul VirtualDisplay : on le garde
 * donc vivant pendant toute la session et on le redimensionne en cas de rotation.)
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val onFrame: () -> Unit,
) {
    private val thread = HandlerThread("screen-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()

    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var latest: Image? = null

    var width = 0; private set
    var height = 0; private set

    fun start() {
        val (w, h, dpi) = screenSize()
        width = w; height = h
        val r = newReader(w, h)
        reader = r
        display = projection.createVirtualDisplay(
            "manhwa-traducteur", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, handler,
        )
    }

    /** À appeler après une rotation. */
    fun refreshSize() = handler.post {
        val (w, h, dpi) = screenSize()
        if (w == width && h == height) return@post
        val old = reader
        val r = newReader(w, h)
        synchronized(lock) {
            latest?.close(); latest = null
            width = w; height = h
        }
        reader = r
        display?.resize(w, h, dpi)
        display?.surface = r.surface
        old?.close()
    }

    /** Copie de la dernière image de l'écran, ou null si aucune n'est encore arrivée. */
    fun grab(): Bitmap? = synchronized(lock) {
        val img = latest ?: return null
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val buffer = plane.buffer.apply { rewind() }
        val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, img.width, img.height).also { padded.recycle() }
    }

    fun stop() {
        display?.release(); display = null
        synchronized(lock) { latest?.close(); latest = null }
        reader?.close(); reader = null
        thread.quitSafely()
    }

    private fun newReader(w: Int, h: Int): ImageReader =
        ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (_: IllegalStateException) { null }
                    ?: return@setOnImageAvailableListener
                synchronized(lock) {
                    latest?.close()
                    latest = img
                }
                onFrame()
            }, handler)
        }

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        val dpi = context.resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            Triple(b.width(), b.height(), dpi)
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(m)
            Triple(m.widthPixels, m.heightPixels, dpi)
        }
    }
}
