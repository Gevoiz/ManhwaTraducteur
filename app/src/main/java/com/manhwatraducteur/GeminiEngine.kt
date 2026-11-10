package com.manhwatraducteur

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class GeminiException(message: String) : Exception(message)

/**
 * Traduction par l'IA Gemini (clé API gratuite sur Google AI Studio).
 * On envoie la capture d'écran réduite + la liste des textes OCR : le modèle corrige les erreurs
 * de lecture grâce à l'image et traduit toute la page d'un coup, en gardant un petit historique
 * pour que les noms et le ton restent cohérents d'une page à l'autre.
 */
class GeminiEngine(
    private val apiKey: String,
    model: String,
    override val source: String,
    override val target: String,
) : TranslationEngine {

    val model = model.trim().removePrefix("models/").ifBlank { DEFAULT_GEMINI_MODEL }
    private val history = ArrayDeque<Pair<String, String>>()
    /** Réflexion minimale : réponse bien plus rapide, largement suffisant pour traduire. */
    @Volatile private var fastThinking = true

    override suspend fun translateAll(texts: List<String>, screenshot: Bitmap?): List<String> =
        withContext(Dispatchers.IO) {
            val request = buildRequest(texts, screenshot).toString().toByteArray(Charsets.UTF_8)
            val (code, body) = try {
                post(request)
            } catch (e: java.io.IOException) {
                if (e is java.net.SocketTimeoutException) throw GeminiException("pas de réponse (réseau lent ?)")
                // Une connexion réutilisée a pu être fermée par le serveur entre-temps : un seul nouvel essai.
                post(request)
            }
            if (code == 400 && fastThinking && body.contains("thinking", ignoreCase = true)) {
                // Ce modèle n'accepte pas le réglage de réflexion : on réessaie sans.
                fastThinking = false
                return@withContext translateAll(texts, screenshot)
            }
            if (code !in 200..299) throw GeminiException(describeError(code, body))
            val result = parse(body, texts)
            remember(texts, result)
            result
        }

    private fun post(request: ByteArray): Pair<Int, String> {
        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 20_000
            doOutput = true
            setFixedLengthStreamingMode(request.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        // Pas de disconnect() en cas de succès : la connexion reste ouverte et la suivante évite la poignée de main TLS.
        try {
            conn.outputStream.use { it.write(request) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to body
        } catch (e: java.io.IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun buildRequest(texts: List<String>, screenshot: Bitmap?): JSONObject {
        val src = languageName(source)
        val tgt = languageName(target)
        val system = """
            Tu es un traducteur professionnel de manhwas, webtoons et mangas. On te donne la capture d'écran d'une page et la liste des textes repérés dessus par OCR. Traduis chaque texte du $src vers le $tgt.
            Règles :
            - L'OCR fait des fautes (lettres ou chiffres confondus, mots coupés ou fusionnés) : appuie-toi sur l'image pour retrouver le texte exact avant de traduire.
            - Traduis de façon naturelle et fluide, comme une scantrad de qualité : registre oral dans les dialogues, phrases courtes, ponctuation expressive conservée.
            - Garde les noms propres, techniques, objets, rangs et systèmes cohérents avec l'historique fourni. Le texte entre crochets [ ] correspond souvent à une fenêtre de système : garde les crochets.
            - Adapte les onomatopées si c'est naturel en $tgt.
            - Si un élément est du bruit OCR illisible ou ne fait pas partie de l'histoire (boutons ou menus du site, filigrane, publicité), renvoie une chaîne vide pour lui.
            - Réponds UNIQUEMENT avec un tableau JSON de exactement ${texts.size} chaînes, dans le même ordre que la liste.
        """.trimIndent()

        val prompt = buildString {
            if (history.isNotEmpty()) {
                appendLine("Traductions précédentes du même chapitre (pour la cohérence) :")
                history.forEach { (s, t) -> appendLine("- \"$s\" → \"$t\"") }
                appendLine()
            }
            appendLine("Textes détectés (de haut en bas) :")
            texts.forEachIndexed { i, t -> appendLine("${i + 1}. $t") }
        }

        val parts = JSONArray()
        screenshot?.let { parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", encode(it)))) }
        parts.put(JSONObject().put("text", prompt))

        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put(
                "generationConfig", JSONObject()
                    .put("temperature", 0.3)
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "STRING")))
                    .apply { if (fastThinking) put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal")) }
            )
    }

    private fun parse(body: String, texts: List<String>): List<String> {
        val candidate = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?: throw GeminiException("réponse vide (contenu bloqué ?)")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException("réponse vide (${candidate.optString("finishReason")})")
        val raw = buildString {
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                if (!p.optBoolean("thought")) append(p.optString("text"))
            }
        }
        val start = raw.indexOf('['); val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) throw GeminiException("réponse illisible")
        val arr = JSONArray(raw.substring(start, end + 1))
        return List(texts.size) { i -> if (i < arr.length()) arr.optString(i, "").trim() else texts[i] }
    }

    private fun remember(texts: List<String>, result: List<String>) {
        texts.zip(result).filter { it.second.isNotBlank() }.forEach {
            history.addLast(it.first.take(120) to it.second.take(120))
        }
        while (history.size > 15) history.removeFirst()
    }

    private fun encode(bmp: Bitmap): String {
        val w = 720
        val scaled = if (bmp.width > w) Bitmap.createScaledBitmap(bmp, w, bmp.height * w / bmp.width, true) else bmp
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun describeError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).getJSONObject("error").optString("message") }.getOrNull().orEmpty()
        return when {
            code == 429 -> "quota gratuit atteint, réessaie dans une minute"
            code == 404 -> "modèle « $model » introuvable"
            msg.contains("API key", ignoreCase = true) || code == 401 || code == 403 -> "clé API invalide"
            else -> "erreur $code ${msg.take(120)}"
        }
    }

    override fun close() {}
}
