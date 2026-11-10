package com.manhwatraducteur

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class TranslatorService : Service(), FloatingBubble.Listener {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val ACTION_STOP = "com.manhwatraducteur.STOP"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1
        private const val TAG = "ManhwaTraducteur"

        @Volatile var running = false
            private set
    }

    private enum class State { IDLE, BUSY, SHOWING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var settings: Settings

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private val tracker = ScrollTracker()
    private var bubble: FloatingBubble? = null
    private var overlay: TranslationOverlay? = null
    private var ocr: OcrEngine? = null
    private var mlkit: MlKitEngine? = null
    private var gemini: GeminiEngine? = null

    private var state = State.IDLE
    private var ignoreFramesUntil = 0L
    private var lastFrameAt = 0L
    private var changedSinceTranslate = true
    private var lostFrames = 0
    private var currentLead = 0
    private var missingKeyWarned = false
    private val cache = TranslationCache()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        settings = Settings(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (projection != null) return START_NOT_STICKY

        // Doit précéder getMediaProjection() depuis Android 14.
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = data?.let { runCatching { mpm.getMediaProjection(code, it) }.getOrNull() }
        if (p == null) { stopSelf(); return START_NOT_STICKY }

        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        projection = p

        capturer = ScreenCapturer(this, p, tracker) { res, moving -> main.post { onFrame(res, moving) } }.also { it.start() }
        setupWindows()
        running = true
        main.postDelayed(autoTick, 500)
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ fenêtres

    private fun setupWindows() {
        overlay = TranslationOverlay(this, wm)
        bubble = FloatingBubble(this, wm, this).also {
            it.setIdleLabel(settings.targetLang.uppercase())
            it.attach()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        clearTranslations()
        capturer?.refreshSize()
    }

    // ------------------------------------------------------------------ bulle

    override fun onTap() = translateScreen()

    override fun onLongPress() = clearTranslations()

    override fun onMoved() {
        // Déplacer la bulle redessine l'écran : ce n'est pas un défilement.
        ignoreFramesUntil = SystemClock.uptimeMillis() + 400
        updateMasks()
    }

    // ------------------------------------------------------------------ suivi de l'écran

    private fun onFrame(res: ScrollTracker.Result?, moving: Boolean) {
        val now = SystemClock.uptimeMillis()
        val ov = overlay ?: return

        // Les anciennes traductions continuent de suivre la page pendant qu'on prépare les nouvelles.
        val following = state == State.SHOWING || (state == State.BUSY && ov.hasItems)
        if (following && settings.followScroll && res != null) {
            when (res) {
                is ScrollTracker.Result.Offset -> {
                    lostFrames = 0
                    val target = res.dy + res.lead
                    if (target != ov.offsetY) {
                        // Un écart d'un ou deux pixels n'est que du bruit : il ne retarde pas le mode auto.
                        if (abs(target - ov.offsetY) > 2 || res.lead != 0) {
                            lastFrameAt = now
                            changedSinceTranslate = true
                        }
                        currentLead = res.lead
                        ov.setOffset(target)
                        updateMasks()
                    }
                }
                ScrollTracker.Result.Lost -> if (++lostFrames >= 3) {
                    if (state == State.BUSY) { ov.clear(); tracker.reset() } else clearTranslations()
                    lastFrameAt = now
                    changedSinceTranslate = true
                }
            }
            return
        }

        // Une petite animation (pub, GIF…) n'est pas un défilement.
        if (state == State.BUSY || now < ignoreFramesUntil || !moving) return
        lastFrameAt = now
        changedSinceTranslate = true
        if (state == State.SHOWING) clearTranslations()
    }

    /** Indique au suivi les zones occupées par nos propres fenêtres, à ignorer. */
    private fun updateMasks() {
        val ov = overlay ?: return
        // Pendant un glissement rapide, nos cartouches sont capturés un peu décalés : marge plus large.
        val rects = ov.boxRects(dp(6), dp(40) + abs(currentLead) * 2).toMutableList()
        bubble?.let { rects += it.screenRect().apply { inset(-dp(8), -dp(8)) } }
        tracker.masks = rects
    }

    private val autoTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (settings.autoMode && state != State.BUSY && changedSinceTranslate && now - lastFrameAt > 250) {
                // Avec le suivi, on ne retraduit que si une partie de l'écran est nouvelle.
                // (Les bulles déjà traduites sont en mémoire : seules les nouvelles coûtent du temps.)
                val scrolled = abs(overlay?.offsetY ?: 0) > (capturer?.height ?: 0) * 0.06
                if (state == State.IDLE || scrolled) translateScreen()
            }
            main.postDelayed(this, 100)
        }
    }

    private fun clearTranslations() {
        overlay?.clear()
        tracker.reset()
        lostFrames = 0
        if (state == State.SHOWING) state = State.IDLE
        ignoreFramesUntil = SystemClock.uptimeMillis() + 250
    }

    // ------------------------------------------------------------------ traduction

    private fun ocrEngine(): OcrEngine {
        val src = settings.sourceLang
        if (ocr?.lang != src) { ocr?.close(); ocr = OcrEngine(src) }
        return ocr!!
    }

    private fun mlkitEngine(): MlKitEngine {
        val src = settings.sourceLang; val tgt = settings.targetLang
        val m = mlkit
        if (m != null && m.source == src && m.target == tgt) return m
        m?.close()
        return MlKitEngine(src, tgt).also { mlkit = it }
    }

    /** Le moteur Gemini si choisi et configuré, sinon null. */
    private fun geminiEngine(): GeminiEngine? {
        if (settings.engine != ENGINE_GEMINI) return null
        val key = settings.geminiKey
        if (key.isBlank()) {
            if (!missingKeyWarned) toast("Ajoute ta clé Gemini dans l'appli. En attendant : traduction hors-ligne.")
            missingKeyWarned = true
            return null
        }
        val src = settings.sourceLang; val tgt = settings.targetLang
        val model = settings.geminiModel.ifBlank { DEFAULT_GEMINI_MODEL }
        val g = gemini
        if (g != null && g.source == src && g.target == tgt && g.model == model.removePrefix("models/")) return g
        return GeminiEngine(key, model, src, tgt).also { gemini = it }
    }

    private fun translateScreen() {
        if (state == State.BUSY) return
        val cap = capturer ?: return
        val ov = overlay ?: return
        val b = bubble ?: return

        state = State.BUSY
        val startedAt = SystemClock.uptimeMillis()
        scope.launch {
            var shown = false
            try {
                // 1. On masque un instant ce qui nous appartient et on attend une image « propre » de l'écran.
                //    Les anciennes traductions réapparaissent aussitôt après, le temps de préparer les nouvelles.
                ov.setVisible(false)
                b.setHidden(true)
                awaitCleanFrame(cap)
                val bitmap = withContext(Dispatchers.Default) { cap.grab() }
                ov.setVisible(true)
                b.setHidden(false)
                b.setStatus("…")
                if (bitmap == null) { flash("!"); return@launch }
                b.setIdleLabel(settings.targetLang.uppercase())

                try {
                    // 2. OCR
                    // Zone morte en bas : barre de navigation + un bandeau réglable (boutons « chapitre suivant »…)
                    val deadZone = bitmap.height * settings.bottomDeadZone / 100
                    val ignoreBelow = bitmap.height - navigationBarHeight() - deadZone
                    val bubbles = ocrEngine().recognize(bitmap, statusBarHeight(), ignoreBelow)
                    if (bubbles.isEmpty()) { ov.clear(); tracker.reset(); flash("∅"); return@launch }
                    val texts = bubbles.map { it.text }

                    // 3. Les bulles déjà traduites sortent de la mémoire et s'affichent tout de suite ;
                    //    seules les nouvelles partent au moteur de traduction.
                    val g = geminiEngine()
                    cache.setScope(
                        if (g != null) "gemini|${g.model}|${g.source}|${g.target}"
                        else "mlkit|${settings.sourceLang}|${settings.targetLang}"
                    )
                    val result = withContext(Dispatchers.Default) { texts.map { cache.get(it) } }.toMutableList()
                    val missing = result.indices.filter { result[it] == null }

                    suspend fun show(): Boolean {
                        val items = bubbles.indices
                            .filter { !result[it].isNullOrBlank() }
                            .map { TranslationOverlay.Item(bubbles[it], result[it]!!) }
                        if (items.isEmpty()) return false
                        withContext(Dispatchers.Default) { tracker.setReference(bitmap) }
                        lostFrames = 0
                        ov.setItems(items, cap.width)
                        b.bringToFront()
                        updateMasks()
                        return true
                    }

                    if (missing.isNotEmpty()) {
                        if (missing.size < texts.size && show()) shown = true
                        val todo = missing.map { texts[it] }
                        val fresh = translate(g, todo, bitmap, b)
                        missing.forEachIndexed { i, idx ->
                            result[idx] = fresh.texts[i]
                            if (fresh.cacheable) cache.put(todo[i], fresh.texts[i])
                        }
                    }

                    // 4. Affichage + référence pour le suivi du défilement
                    if (show()) shown = true
                    else { ov.clear(); tracker.reset(); shown = false; flash("∅"); return@launch }
                    b.setStatus(null)
                } finally {
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Échec de la traduction", e)
                ov.setVisible(true)
                b.setHidden(false)
                flash("!")
                val msg = e.message.orEmpty()
                toast(
                    if (msg.contains("download", ignoreCase = true) || msg.contains("module", ignoreCase = true))
                        "Modèle en cours de téléchargement, réessaie dans quelques secondes."
                    else "Erreur : $msg"
                )
            } finally {
                shown = shown && ov.hasItems
                if (!shown) { ov.clear(); tracker.reset() }
                state = if (shown) State.SHOWING else State.IDLE
                // Si la page a bougé pendant la traduction, le mode auto pourra relancer.
                changedSinceTranslate = lastFrameAt > startedAt
                lostFrames = 0
                ignoreFramesUntil = SystemClock.uptimeMillis() + 400
            }
        }
    }

    /** Attend que l'écran capturé ne montre plus nos fenêtres (au moins 2 nouvelles images). */
    private suspend fun awaitCleanFrame(cap: ScreenCapturer) {
        val t0 = SystemClock.uptimeMillis()
        val first = cap.frameCount
        while (true) {
            val elapsed = SystemClock.uptimeMillis() - t0
            if (elapsed >= 250) return
            if (elapsed >= 50 && cap.frameCount >= first + 2) return
            delay(8)
        }
    }

    private class Fresh(val texts: List<String>, val cacheable: Boolean)

    /** Gemini si configuré, sinon (ou en cas d'échec) ML Kit hors-ligne. */
    private suspend fun translate(g: GeminiEngine?, texts: List<String>, bitmap: Bitmap, b: FloatingBubble): Fresh {
        if (g != null) {
            try {
                return Fresh(g.translateAll(texts, bitmap), true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Gemini a échoué", e)
                toast("Gemini : ${e.message} → traduction hors-ligne")
            }
        }
        val m = mlkitEngine()
        if (!m.ready) {
            b.setStatus("↓")
            toast("Téléchargement du modèle de traduction (une seule fois)…")
        }
        // Le repli ML Kit après un échec de Gemini n'est pas mémorisé : on retentera Gemini.
        return Fresh(m.translateAll(texts, null), g == null)
    }

    private fun flash(symbol: String) {
        bubble?.setStatus(symbol)
        main.postDelayed({ if (state != State.BUSY) bubble?.setStatus(null) }, 1200)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Traducteur actif", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle("Manhwa Traducteur actif")
            .setContentText("Touche la bulle flottante pour traduire l'écran")
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        scope.cancel()
        bubble?.detach(); bubble = null
        overlay?.clear(); overlay = null
        capturer?.stop(); capturer = null
        tracker.reset()
        projection?.stop(); projection = null
        ocr?.close(); ocr = null
        mlkit?.close(); mlkit = null
        gemini = null
        super.onDestroy()
    }
}
