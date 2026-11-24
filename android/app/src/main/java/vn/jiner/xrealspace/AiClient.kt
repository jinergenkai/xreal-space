package vn.jiner.xrealspace

import android.os.Handler
import android.os.Looper
import vn.jiner.xrealspace.core.JevProtocol
import vn.jiner.xrealspace.core.LlmProtocol
import vn.jiner.xrealspace.core.VoiceCommands
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Network side of speech -> intent: TypeSafe Jev (decision model) and LLMs (Anthropic or OpenAI-compatible).
 * All calls run on one background thread; callbacks come back on the main thread.
 * API keys are never logged.
 */
object AiClient {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class HttpError(val code: Int, val body: String) : Exception("HTTP $code")

    private fun post(url: String, body: String, headers: Map<String, String>, timeoutMs: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpError(code, text)
            return text
        } finally { c.disconnect() }
    }

    private fun <T> async(work: () -> T, done: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(work)
            main.post { done(r) }
        }
    }

    fun describe(e: Throwable): String = when (e) {
        is HttpError -> "HTTP ${e.code}: " + (LlmProtocol.errorText(e.body) ?: e.body.take(160))
        else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
    }

    // ------------------------------------------------------------------ LLM
    private fun llmComplete(system: String, user: String, maxTokens: Int): String {
        val st = SpaceState
        val (body, headers) = if (st.llmProvider == "anthropic") {
            LlmProtocol.anthropicBody(st.llmModel, system, user, maxTokens) to
                mapOf("x-api-key" to st.llmKey, "anthropic-version" to "2023-06-01")
        } else {
            LlmProtocol.openAiBody(st.llmModel, system, user, maxTokens) to
                (if (st.llmKey.isNotBlank()) mapOf("Authorization" to "Bearer ${st.llmKey}") else emptyMap())
        }
        val resp = post(st.llmBaseUrl, body, headers, 30000)
        return LlmProtocol.responseText(resp) ?: throw IllegalStateException("unexpected response: ${resp.take(120)}")
    }

    /** Spoken command -> list of app commands via the LLM (function calling as JSON). */
    fun llmCommand(text: String, done: (Result<LlmProtocol.Reply>) -> Unit) =
        async({ LlmProtocol.parseReply(llmComplete(LlmProtocol.SYSTEM_PROMPT, text, 500)) }, done)

    fun llmAsk(question: String, done: (Result<String>) -> Unit) =
        async({ llmComplete("Bạn là trợ lý trên kính AR. Trả lời bằng tiếng Việt, ngắn gọn (tối đa 4 câu), không markdown.", question, 400) }, done)

    fun llmSummarize(title: String, pageText: String, done: (Result<String>) -> Unit) =
        async({
            llmComplete("Tóm tắt trang web cho người đang đeo kính AR. Tiếng Việt, tối đa 5 gạch đầu dòng ngắn, mỗi dòng bắt đầu bằng '• '. Không markdown khác.",
                "Tiêu đề: $title\n\n${pageText.take(12000)}", 500)
        }, done)

    // ------------------------------------------------------------------ Jev
    /** Spoken command -> one app command via TypeSafe Jev (classifier); params come from the transcript. */
    fun jevCommand(text: String, done: (Result<Pair<VoiceCommands.Cmd, JevProtocol.Decision>>) -> Unit) =
        async({
            val st = SpaceState
            val resp = post(st.jevBaseUrl, JevProtocol.requestBody(st.jevModel, text), mapOf("Authorization" to "Bearer ${st.jevKey}"), 15000)
            val d = JevProtocol.parseDecision(resp) ?: throw IllegalStateException("unexpected Jev response: ${resp.take(160)}")
            JevProtocol.toCmd(d, text) to d
        }, done)
}
