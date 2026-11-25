package vn.jiner.xrealspace

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import vn.jiner.xrealspace.core.SpeakMode
import vn.jiner.xrealspace.core.Speech
import java.util.Locale

/**
 * Speaks through the earphones / glasses speakers with the phone's own text-to-speech (free, offline once the
 * Vietnamese voice is installed). Music ducks while it talks. Used for notifications, turn-by-turn, read-outs.
 */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var vietnamese = false
    private var warned = false
    private lateinit var app: Context
    private var am: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private var counter = 0

    fun init(ctx: Context) {
        if (tts != null) return
        app = ctx.applicationContext
        am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) { SpaceState.log("TTS init failed $status"); return@TextToSpeech }
            val t = tts ?: return@TextToSpeech
            val r = t.setLanguage(Locale("vi", "VN"))
            vietnamese = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            if (!vietnamese) t.language = Locale.US
            t.setSpeechRate(1.05f)
            t.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { if (t.isSpeaking.not()) releaseFocus() }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { releaseFocus() }
            })
            SpaceState.log("TTS ready, vietnamese=$vietnamese")
        }
    }

    val riding get() = SpaceState.speedLocked || Navigation.active

    /** Speak if the user's setting allows it right now. [force] = the user explicitly asked ("đọc thông báo"). */
    fun say(text: String, flush: Boolean = false, force: Boolean = false) {
        val mode = SpaceState.speakMode
        if (text.isBlank() || mode == SpeakMode.OFF) return
        if (!force && !Speech.shouldSpeak(mode, riding)) return
        val t = tts ?: return
        if (!ready) return
        if (!vietnamese && !warned) {
            warned = true
            SpaceState.notify("Giọng đọc", "Máy chưa có giọng tiếng Việt: cài \"Speech Services by Google\" → Cài đặt → tải tiếng Việt", Colors.AMBER, 8.0)
        }
        requestFocus()
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "xs${counter++}")
    }

    fun stop() { tts?.stop(); releaseFocus() }

    /** music / video lowers its volume while we talk */
    private fun requestFocus() {
        val m = am ?: return
        if (Build.VERSION.SDK_INT >= 26) {
            val f = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).build().also { focus = it }
            m.requestAudioFocus(f)
        }
    }

    private fun releaseFocus() { if (Build.VERSION.SDK_INT >= 26) focus?.let { am?.abandonAudioFocusRequest(it) } }
}
