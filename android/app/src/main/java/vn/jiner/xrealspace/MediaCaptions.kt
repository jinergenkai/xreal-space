package vn.jiner.xrealspace

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Subtitles from what the phone is PLAYING (the film in the glasses, or YouTube / Netflix-like apps that allow it)
 * instead of the room microphone: Android 10+ playback capture -> 16 kHz mono -> Vosk.
 * Only "media / game / unknown" audio is captured, so our own spoken read-outs (navigation usage) are not.
 */
object MediaCaptions {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    val supported get() = Build.VERSION.SDK_INT >= 29

    @SuppressLint("MissingPermission", "NewApi")
    fun start(projection: MediaProjection, model: Model, onPartial: (String) -> Unit, onFinal: (String) -> Unit) {
        stop()
        if (!supported) return
        running = true
        thread = Thread {
            var rec: AudioRecord? = null
            var vosk: Recognizer? = null
            try {
                val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, 16000))
                    .setAudioPlaybackCaptureConfig(cfg).build()
                vosk = Recognizer(model, 16000f)
                rec.startRecording()
                SpaceState.log("media captions: capturing playback audio")
                val buf = ByteArray(6400)                       // 0.2 s
                var lastPartial = ""
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (vosk.acceptWaveForm(buf, n)) {
                        val t = field(vosk.result, "text")
                        lastPartial = ""
                        if (t.isNotBlank()) main.post { onFinal(t) }
                    } else {
                        val p = field(vosk.partialResult, "partial")
                        if (p != lastPartial) { lastPartial = p; main.post { onPartial(p) } }
                    }
                }
            } catch (e: Throwable) {
                SpaceState.log("media captions error: $e")
                main.post { SpaceState.notify("Phụ đề phim", "Không thu được âm thanh: ${e.message}", Colors.RED, 4.0) }
            } finally {
                try { rec?.stop() } catch (_: Exception) {}
                rec?.release(); vosk?.close()
            }
        }.apply { name = "media-captions"; start() }
    }

    fun stop() { running = false; thread = null }
    val active get() = running

    private fun field(json: String, key: String) = try { JSONObject(json).optString(key, "") } catch (_: Exception) { "" }
}
