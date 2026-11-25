package vn.jiner.xrealspace

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import vn.jiner.xrealspace.core.MiniJson
import vn.jiner.xrealspace.core.WakeWord
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Free voice input, one microphone owner at a time:
 *  - WAKE:       Vosk Vietnamese, grammar = wake phrases + [unk] ("kính ơi")
 *  - COMMAND:    one utterance, Vosk free-form offline or Google (online)
 *  - CONTINUOUS: live captions / teleprompter, Vosk vi or en, partial + final results
 * A command interrupts continuous listening and resumes it afterwards; wake listening runs only when nothing else does.
 */
class VoiceInput(private val ctx: Context, private val onCommandText: (String) -> Unit) {
    companion object {
        const val RATE = 16000.0f
        /** lang -> (folder name, download url). Small models, Apache 2.0, ~32–40 MB each. */
        val MODELS = mapOf(
            "vi" to ("vosk-model-small-vn-0.4" to "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"),
            "en" to ("vosk-model-small-en-us-0.15" to "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        )
    }

    private val main = Handler(Looper.getMainLooper())
    private val models = HashMap<String, Model>()
    private val loading = HashSet<String>()
    private var wakeService: SpeechService? = null
    private var cmdService: SpeechService? = null
    private var contService: SpeechService? = null
    private var google: SpeechRecognizer? = null
    @Volatile var modelStatus = statusFor("vi"); private set
    var capturing = false; private set
    private var cmdTimeout: Runnable? = null

    /** continuous session to resume after a command */
    private data class Cont(val lang: String, val onPartial: (String) -> Unit, val onFinal: (String) -> Unit)
    private var cont: Cont? = null

    val modelReady get() = models.containsKey("vi")

    private fun dirFor(lang: String) = File(ctx.filesDir, MODELS.getValue(lang).first)
    private fun downloaded(lang: String) = dirFor(lang).let { File(it, "am").exists() || File(it, "conf").exists() }
    private fun statusFor(lang: String) = if (downloaded(lang)) "đã tải" else "chưa tải"

    // ------------------------------------------------------------------ models
    fun ensureModel(done: (Boolean) -> Unit) = ensureModel("vi", done)

    /** a loaded model (after ensureModel), for recognizers fed by other audio sources */
    fun model(lang: String): Model? = models[lang]

    /** Downloads (once) and loads a Vosk model for [lang]. */
    fun ensureModel(lang: String, done: (Boolean) -> Unit) {
        if (models.containsKey(lang)) { done(true); return }
        if (!loading.add(lang)) { main.postDelayed({ ensureModel(lang, done) }, 500); return }
        Thread {
            try {
                if (!downloaded(lang)) { modelStatus = "$lang: đang tải 0%"; download(lang) }
                modelStatus = "$lang: đang nạp…"
                val m = Model(dirFor(lang).absolutePath)
                main.post { models[lang] = m; loading.remove(lang); modelStatus = "sẵn sàng (${models.keys.joinToString()})"; SpaceState.log("Vosk model $lang loaded"); done(true) }
            } catch (e: Throwable) {
                modelStatus = "$lang lỗi: ${e.message}"
                SpaceState.log("Vosk model $lang error: $e")
                main.post { loading.remove(lang); done(false) }
            }
        }.start()
    }

    private fun download(lang: String) {
        val (name, url) = MODELS.getValue(lang)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        val total = c.contentLengthLong
        var read = 0L
        val tmp = File(ctx.filesDir, "$name.partial")
        tmp.deleteRecursively(); tmp.mkdirs()
        c.inputStream.use { raw ->
            val counting = object : java.io.FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { read += n; if (total > 0) modelStatus = "$lang: đang tải ${read * 100 / total}%" }
                    return n
                }
            }
            ZipInputStream(counting).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val out = File(tmp, e.name)
                    if (!out.canonicalPath.startsWith(tmp.canonicalPath)) continue // zip-slip guard
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        val inner = File(tmp, name).takeIf { it.isDirectory } ?: tmp
        val dest = dirFor(lang)
        dest.deleteRecursively()
        if (!inner.renameTo(dest)) throw IllegalStateException("cannot move model")
        tmp.deleteRecursively()
    }

    private fun text(hypothesis: String): String = try {
        @Suppress("UNCHECKED_CAST")
        ((MiniJson.parse(hypothesis) as? Map<String, Any?>)?.let { it["text"] ?: it["partial"] } as? String).orEmpty().trim()
    } catch (e: Exception) { "" }

    private fun listener(onPartial: (String) -> Unit, onResult: (String) -> Unit, onEnd: () -> Unit) =
        object : org.vosk.android.RecognitionListener {
            override fun onPartialResult(hypothesis: String) { val t = text(hypothesis); main.post { onPartial(t) } }
            override fun onResult(hypothesis: String) { val t = text(hypothesis); main.post { onResult(t) } }
            override fun onFinalResult(hypothesis: String) { val t = text(hypothesis); main.post { onResult(t); onEnd() } }
            override fun onError(e: Exception) { SpaceState.log("vosk error: $e"); main.post { onEnd() } }
            override fun onTimeout() { main.post { onEnd() } }
        }

    // ------------------------------------------------------------------ wake phrase
    fun startWake() {
        if (!SpaceState.wakeEnabled || capturing || wakeService != null || cont != null) return
        val m = models["vi"] ?: run { ensureModel("vi") { ok -> if (ok) startWake() }; return }
        try {
            val rec = Recognizer(m, RATE, WakeWord.voskGrammar(SpaceState.wakePhrases))
            val s = SpeechService(rec, RATE)
            s.startListening(listener({}, { t -> handleWake(t) }, {}))
            wakeService = s
            SpaceState.log("Wake listening: ${SpaceState.wakePhrases}")
        } catch (e: Exception) { SpaceState.log("wake start failed: $e") }
    }

    fun stopWake() { wakeService?.let { it.stop(); it.shutdown() }; wakeService = null }

    private fun handleWake(t: String) {
        if (t.isBlank() || t == "[unk]" || !WakeWord.matches(t, SpaceState.wakePhrases)) return
        SpaceState.log("wake: $t")
        captureCommand()
    }

    // ------------------------------------------------------------------ continuous (captions, teleprompter)
    fun startContinuous(lang: String, onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        stopContinuous()
        cont = Cont(lang, onPartial, onFinal)
        stopWake()
        ensureModel(lang) { ok ->
            if (!ok) { SpaceState.notify("Giọng nói", "Không tải được model $lang: $modelStatus", Colors.RED, 4.0); cont = null; return@ensureModel }
            if (!capturing) runContinuous()
        }
    }

    private fun runContinuous() {
        val c = cont ?: return
        val m = models[c.lang] ?: return
        try {
            val s = SpeechService(Recognizer(m, RATE), RATE)
            s.startListening(listener(c.onPartial, { t -> if (t.isNotBlank()) c.onFinal(t) }, {
                // stopped by timeout/error: restart while still wanted
                if (cont === c && contService === s) { contService = null; main.postDelayed({ if (cont === c && !capturing) runContinuous() }, 300) }
            }))
            contService = s
        } catch (e: Exception) { SpaceState.log("continuous start failed: $e") }
    }

    private fun pauseContinuous() { contService?.let { it.stop(); it.shutdown() }; contService = null }

    fun stopContinuous() {
        cont = null
        pauseContinuous()
        main.postDelayed({ startWake() }, 300)
    }

    // ------------------------------------------------------------------ command capture
    /** Push-to-talk or after the wake phrase: capture one utterance and hand it to [onCommandText]. */
    fun captureCommand() {
        if (capturing) return
        stopWake(); pauseContinuous()
        capturing = true
        SpaceState.voiceState = "listening"; SpaceState.voiceHeard = ""
        SpaceState.showGrid(8.0)
        if (SpaceState.sttEngine == SpaceState.SttEngine.GOOGLE || models["vi"] == null) captureGoogle() else captureVosk()
    }

    private fun finishCapture(result: String?) {
        if (!capturing) return
        cmdTimeout?.let { main.removeCallbacks(it) }; cmdTimeout = null
        cmdService?.let { it.stop(); it.shutdown() }; cmdService = null
        capturing = false
        SpaceState.voiceState = ""
        if (!result.isNullOrBlank()) {
            val cleaned = WakeWord.remainder(result, SpaceState.wakePhrases)
            if (cleaned.isNotBlank()) onCommandText(cleaned)
        } else SpaceState.notify("🎤", "Không nghe rõ", Colors.AMBER, 1.5)
        main.postDelayed({ if (cont != null) runContinuous() else startWake() }, 400)
    }

    private fun captureVosk() {
        val m = models["vi"] ?: return captureGoogle()
        try {
            val s = SpeechService(Recognizer(m, RATE), RATE)
            s.startListening(listener(
                { t -> SpaceState.voiceHeard = t },
                { t -> if (t.isNotBlank()) finishCapture(t) },
                { finishCapture(SpaceState.voiceHeard) }))
            cmdService = s
            cmdTimeout = Runnable { finishCapture(SpaceState.voiceHeard) }.also { main.postDelayed(it, 7000) }
        } catch (e: Exception) { SpaceState.log("vosk cmd start failed: $e"); finishCapture(null) }
    }

    private fun captureGoogle() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            SpaceState.notify("Giọng nói", "Không có Google speech: dùng Vosk offline (tải model trong Cài đặt AI)", Colors.RED, 4.0)
            capturing = false; SpaceState.voiceState = ""; return
        }
        val sr = google ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { google = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) { finishCapture(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
            override fun onPartialResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { SpaceState.voiceHeard = it } }
            override fun onError(error: Int) { SpaceState.log("google stt error $error"); finishCapture(SpaceState.voiceHeard.ifBlank { null }) }
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    fun release() {
        stopWake(); cont = null; pauseContinuous()
        cmdService?.let { it.stop(); it.shutdown() }; cmdService = null
        google?.destroy(); google = null
        models.values.forEach { it.close() }; models.clear()
    }
}
