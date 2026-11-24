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
 * Free voice input:
 *  - Wake phrase ("kính ơi"): Vosk offline Vietnamese model, grammar limited to the wake phrases + [unk],
 *    so it only ever outputs a wake phrase or [unk]. Free (Apache 2.0), no account, no network after download.
 *  - Command speech-to-text: Vosk free-form offline (free) or Android's Google recognizer (more accurate, online).
 * One microphone owner at a time: wake listening pauses while a command is captured.
 */
class VoiceInput(private val ctx: Context, private val onCommandText: (String) -> Unit) {
    companion object {
        const val MODEL_NAME = "vosk-model-small-vn-0.4"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-vn-0.4.zip"
        const val RATE = 16000.0f
    }

    private val main = Handler(Looper.getMainLooper())
    private val modelDir = File(ctx.filesDir, MODEL_NAME)
    private var model: Model? = null
    private var wakeService: SpeechService? = null
    private var cmdService: SpeechService? = null
    private var google: SpeechRecognizer? = null
    @Volatile var modelStatus = if (File(modelDir, "am").exists() || File(modelDir, "conf").exists()) "đã tải" else "chưa tải"; private set
    var capturing = false; private set
    private var cmdTimeout: Runnable? = null

    val modelReady get() = model != null

    // ------------------------------------------------------------------ model
    /** Downloads (~32 MB, once) and loads the Vietnamese Vosk model. */
    fun ensureModel(done: (Boolean) -> Unit) {
        if (model != null) { done(true); return }
        Thread {
            try {
                if (!(File(modelDir, "am").exists() || File(modelDir, "conf").exists())) {
                    modelStatus = "đang tải 0%"
                    download(MODEL_URL, ctx.filesDir)
                }
                modelStatus = "đang nạp…"
                val m = Model(modelDir.absolutePath)
                main.post { model = m; modelStatus = "sẵn sàng"; SpaceState.log("Vosk model loaded"); done(true) }
            } catch (e: Throwable) {
                modelStatus = "lỗi: ${e.message}"
                SpaceState.log("Vosk model error: $e")
                main.post { done(false) }
            }
        }.start()
    }

    private fun download(url: String, into: File) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 60000
        val total = c.contentLengthLong
        var read = 0L
        val tmp = File(into, "$MODEL_NAME.partial")
        tmp.deleteRecursively(); tmp.mkdirs()
        c.inputStream.use { raw ->
            val counting = object : java.io.FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) { read += n; if (total > 0) modelStatus = "đang tải ${read * 100 / total}%" }
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
        // the zip has a top folder named like the model
        val inner = File(tmp, MODEL_NAME).takeIf { it.isDirectory } ?: tmp
        modelDir.deleteRecursively()
        if (!inner.renameTo(modelDir)) throw IllegalStateException("cannot move model")
        tmp.deleteRecursively()
    }

    // ------------------------------------------------------------------ wake phrase
    fun startWake() {
        if (!SpaceState.wakeEnabled || capturing || wakeService != null) return
        val m = model ?: run { ensureModel { ok -> if (ok) startWake() }; return }
        try {
            val rec = Recognizer(m, RATE, WakeWord.voskGrammar(SpaceState.wakePhrases))
            val s = SpeechService(rec, RATE)
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String) {}
                override fun onResult(hypothesis: String) { handleWake(text(hypothesis)) }
                override fun onFinalResult(hypothesis: String) { handleWake(text(hypothesis)) }
                override fun onError(e: Exception) { SpaceState.log("wake error: $e") }
                override fun onTimeout() {}
            })
            wakeService = s
            SpaceState.log("Wake listening: ${SpaceState.wakePhrases}")
        } catch (e: Exception) { SpaceState.log("wake start failed: $e") }
    }

    fun stopWake() { wakeService?.let { it.stop(); it.shutdown() }; wakeService = null }

    private fun handleWake(t: String) {
        if (t.isBlank() || t == "[unk]" || !WakeWord.matches(t, SpaceState.wakePhrases)) return
        main.post {
            SpaceState.log("wake: $t")
            captureCommand()
        }
    }

    private fun text(hypothesis: String): String = try {
        @Suppress("UNCHECKED_CAST")
        ((MiniJson.parse(hypothesis) as? Map<String, Any?>)?.let { it["text"] ?: it["partial"] } as? String).orEmpty().trim()
    } catch (e: Exception) { "" }

    // ------------------------------------------------------------------ command capture
    /** Push-to-talk or after the wake phrase: capture one utterance and hand it to [onCommandText]. */
    fun captureCommand() {
        if (capturing) return
        stopWake()
        capturing = true
        SpaceState.voiceState = "listening"; SpaceState.voiceHeard = ""
        SpaceState.showGrid(8.0)
        if (SpaceState.sttEngine == SpaceState.SttEngine.GOOGLE || model == null) captureGoogle() else captureVosk()
    }

    private fun finishCapture(result: String?) {
        cmdTimeout?.let { main.removeCallbacks(it) }; cmdTimeout = null
        cmdService?.let { it.stop(); it.shutdown() }; cmdService = null
        capturing = false
        SpaceState.voiceState = ""
        if (!result.isNullOrBlank()) {
            val cleaned = WakeWord.remainder(result, SpaceState.wakePhrases)
            if (cleaned.isNotBlank()) onCommandText(cleaned)
        } else SpaceState.notify("🎤", "Không nghe rõ", Colors.AMBER, 1.5)
        main.postDelayed({ startWake() }, 400)
    }

    private fun captureVosk() {
        val m = model ?: return captureGoogle()
        try {
            val rec = Recognizer(m, RATE)
            val s = SpeechService(rec, RATE)
            s.startListening(object : org.vosk.android.RecognitionListener {
                override fun onPartialResult(hypothesis: String) { main.post { SpaceState.voiceHeard = text(hypothesis) } }
                override fun onResult(hypothesis: String) {
                    val t = text(hypothesis)
                    if (t.isNotBlank()) main.post { if (capturing) finishCapture(t) }
                }
                override fun onFinalResult(hypothesis: String) { val t = text(hypothesis); main.post { if (capturing) finishCapture(t) } }
                override fun onError(e: Exception) { main.post { SpaceState.log("vosk cmd error: $e"); if (capturing) finishCapture(null) } }
                override fun onTimeout() { main.post { if (capturing) finishCapture(SpaceState.voiceHeard) } }
            })
            cmdService = s
            cmdTimeout = Runnable { if (capturing) finishCapture(SpaceState.voiceHeard) }.also { main.postDelayed(it, 7000) }
        } catch (e: Exception) { SpaceState.log("vosk cmd start failed: $e"); finishCapture(null) }
    }

    private fun captureGoogle() {
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            SpaceState.notify("Giọng nói", "Không có Google speech: chuyển sang Vosk offline (tải model trong Cài đặt AI)", Colors.RED, 4.0)
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
        stopWake()
        cmdService?.let { it.stop(); it.shutdown() }; cmdService = null
        google?.destroy(); google = null
        model?.close(); model = null
    }
}
