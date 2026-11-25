package vn.jiner.xrealspace

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import vn.jiner.xrealspace.core.AppMatcher
import vn.jiner.xrealspace.core.CaptionBuffer
import vn.jiner.xrealspace.core.CaptionMode
import vn.jiner.xrealspace.core.FastPath
import vn.jiner.xrealspace.core.PrompterTracker
import vn.jiner.xrealspace.core.VoiceCommands

/** Which SpaceView is live right now (glasses Presentation from the activity or the service, or the phone screen). */
object SpaceHost {
    @Volatile var space: SpaceView? = null
}

/**
 * The app's "brain", independent of any Activity so it keeps working while another app (Zalo, Netflix…) is in front
 * and the glasses are hosted by [GlassesService]: voice input, speech -> intent, assistant answers, live captions,
 * teleprompter, launching apps, phone mirror requests.
 */
object Assistant : SpaceState.Controller {
    private lateinit var app: Context
    lateinit var voice: VoiceInput
        private set

    /** set by MainActivity while a page text field is being typed into: the next utterance is dictation */
    var dictationSink: ((String) -> Unit)? = null
    /** remote-control phone: recognized speech is sent to the glasses box instead of being run here */
    var remoteSink: ((String) -> Unit)? = null
    /** set by MainActivity: asks the user for screen-capture consent (needs an Activity) */
    var mirrorRequester: ((afterGrant: (() -> Unit)?) -> Unit)? = null

    // live captions + teleprompter state, read by SpaceView every frame
    val captions = CaptionBuffer()
    @Volatile var captionsVersion = 0; private set
    var prompter: PrompterTracker? = null; private set
    @Volatile var prompterVersion = 0; private set

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        voice = VoiceInput(app) { text -> handleUtterance(text) }
        SpaceState.controller = this
    }

    fun hasMic() = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun pushToTalk() {
        if (!hasMic()) { SpaceState.notify("Micro", "Mở app trên điện thoại và cấp quyền micro", Colors.AMBER, 3.0); return }
        voice.captureCommand()
    }

    // ------------------------------------------------------------------ speech -> intent
    /** Speech text -> intent (rules / Jev / LLM) -> commands. Falls back to rules when the network engine fails. */
    fun handleUtterance(text: String) {
        val st = SpaceState
        st.log("voice: $text")
        st.voiceHeard = text
        dictationSink?.let { sink -> dictationSink = null; sink(text); return }
        remoteSink?.let { send -> send(text); st.notify("🎤 → máy kính", text, Colors.CYAN, 2.0); return }
        val rules = VoiceCommands.parse(text)
        fun runRules(prefix: String = "") {
            if (rules !is VoiceCommands.Cmd.Unknown) st.notify("🎤 $text", prefix + label(rules), Colors.GREEN, 2.0)
            st.execute(rules)
        }
        if (st.intentEngine != SpaceState.IntentEngine.RULES && FastPath.accept(text, rules)) { runRules("⚡ "); return }
        when (st.intentEngine) {
            SpaceState.IntentEngine.RULES -> runRules()
            SpaceState.IntentEngine.JEV -> {
                if (!st.jevReady) { runRules("(chưa có key Jev) "); return }
                st.voiceState = "thinking"
                AiClient.jevCommand(text) { r ->
                    st.voiceState = ""
                    r.onSuccess { (cmd, d) ->
                        st.log("jev: ${d.intent} %.2f site=${d.site} switch=${d.switch}".format(d.confidence))
                        if (cmd is VoiceCommands.Cmd.Unknown && rules !is VoiceCommands.Cmd.Unknown) { runRules("(Jev không chắc) "); return@onSuccess }
                        st.notify("🎤 $text", "Jev: ${d.intent} · %.0f%%".format(d.confidence * 100), Colors.GREEN, 2.0)
                        st.execute(cmd)
                    }.onFailure { e -> st.log("jev failed: ${AiClient.describe(e)}"); runRules("(Jev lỗi, dùng luật) ") }
                }
            }
            SpaceState.IntentEngine.LLM -> {
                if (!st.llmReady) { runRules("(chưa cấu hình LLM) "); return }
                st.voiceState = "thinking"
                AiClient.llmCommand(text) { r ->
                    st.voiceState = ""
                    r.onSuccess { reply ->
                        if (reply.cmds.isEmpty()) { runRules(); return@onSuccess }
                        st.notify("🎤 $text", reply.say ?: reply.cmds.joinToString(" → ") { label(it) }, Colors.GREEN, 2.5)
                        reply.cmds.forEach { st.execute(it) }
                    }.onFailure { e -> st.log("llm failed: ${AiClient.describe(e)}"); runRules("(LLM lỗi, dùng luật) ") }
                }
            }
        }
    }

    fun label(c: VoiceCommands.Cmd): String = when (c) {
        is VoiceCommands.Cmd.Place -> "ô ${c.slot}: ${c.title ?: c.url}"
        is VoiceCommands.Cmd.Open -> "mở ${c.url}"
        is VoiceCommands.Cmd.ClearSlot -> if (c.slot == 0) "xoá màn đang nhìn" else "xoá ô ${c.slot}"
        is VoiceCommands.Cmd.Workspace -> "không gian ${c.name}"
        is VoiceCommands.Cmd.SaveWorkspace -> "lưu không gian ${c.name}"
        is VoiceCommands.Cmd.LaunchApp -> "mở app ${c.name}"
        is VoiceCommands.Cmd.Mirror -> "màn hình điện thoại" + (c.slot?.let { " ô $it" } ?: "")
        is VoiceCommands.Cmd.Captions -> c.mode.title
        else -> c.javaClass.simpleName
    }

    // ------------------------------------------------------------------ SpaceState.Controller
    override fun ask(question: String) {
        val st = SpaceState
        if (!st.llmReady) { st.notify("Trợ lý", "Cần cấu hình LLM (Cài đặt AI) để trả lời câu hỏi", Colors.AMBER, 3.0); return }
        st.voiceState = "thinking"
        AiClient.llmAsk(question) { r ->
            st.voiceState = ""
            r.onSuccess { st.showCard("❓ $question", it.trim()) }.onFailure { st.notify("Trợ lý lỗi", AiClient.describe(it), Colors.RED, 4.0) }
        }
    }

    override fun summarizeFocused() {
        val st = SpaceState
        val web = SpaceHost.space?.focusedWeb() ?: run { st.notify("Tóm tắt", "Không có trang web nào", Colors.AMBER); return }
        if (!st.llmReady) { st.notify("Tóm tắt", "Cần cấu hình LLM (Cài đặt AI)", Colors.AMBER, 3.0); return }
        st.voiceState = "thinking"; st.voiceHeard = "tóm tắt trang"
        web.evaluateJavascript("(function(){return (document.title||'')+'\\n'+(document.body?document.body.innerText:'').slice(0,15000);})()") { raw ->
            val txt = try { org.json.JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw ?: "" }
            val title = txt.substringBefore('\n')
            AiClient.llmSummarize(title, txt.substringAfter('\n')) { r ->
                st.voiceState = ""
                r.onSuccess { st.showCard("📄 ${title.take(60)}", it.trim(), 40.0) }.onFailure { st.notify("Tóm tắt lỗi", AiClient.describe(it), Colors.RED, 4.0) }
            }
        }
    }

    /** "mở Zalo": launch the installed app and make sure the phone mirror shows it on the glasses. */
    override fun launchApp(name: String) {
        val pm = app.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppMatcher.App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        val hit = AppMatcher.find(name, apps) ?: run { SpaceState.notify("Mở app", "Không tìm thấy \"$name\"", Colors.AMBER, 3.0); return }
        val launch = pm.getLaunchIntentForPackage(hit.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val go = {
            try { app.startActivity(launch); SpaceState.notify("Mở app", "${hit.label} → ô điện thoại", Colors.GREEN, 2.0) }
            catch (e: Exception) { SpaceState.notify("Mở app lỗi", "${e.message}", Colors.RED, 3.0) }
        }
        if (MirrorController.active) { ensureMirrorPanel(null); go() } else mirror(null, then = go)
    }

    /** Show the phone screen in a slot (asks for screen-capture consent once per session). */
    override fun mirror(slot: Int?, then: (() -> Unit)?) {
        if (MirrorController.active) { ensureMirrorPanel(slot); then?.invoke(); return }
        val req = mirrorRequester
        if (req != null) req { ensureMirrorPanel(slot); then?.invoke() }
        else {
            // no activity in front: bring it up to ask for consent
            app.startActivity(Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_REQUEST_MIRROR)
                .putExtra("slot", slot ?: 4).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun ensureMirrorPanel(slot: Int?) {
        val st = SpaceState
        val existing = st.config.screens.indexOfFirst { it.url == MIRROR_URL }
        val target = slot ?: if (existing >= 0) null else 4
        if (existing >= 0 && target == null) return
        if (existing >= 0) st.removePanel(existing)
        st.place(target ?: 4, MIRROR_URL, "Điện thoại")
    }

    override fun mirrorOff() {
        MirrorController.stop()
        val i = SpaceState.config.screens.indexOfFirst { it.url == MIRROR_URL }
        if (i >= 0) SpaceState.removePanel(i)
        GlassesService.instance?.refreshForeground()
    }

    override fun phoneBack(): Boolean = ControlService.instance?.back() ?: false
    override fun phoneHome(): Boolean = ControlService.instance?.home() ?: false

    // ------------------------------------------------------------------ live captions / interpreter
    override fun setCaptions(mode: CaptionMode) {
        val st = SpaceState
        if (mode != CaptionMode.OFF && !hasMic()) { st.notify("Phụ đề", "Cần quyền micro", Colors.AMBER, 3.0); return }
        if (prompter != null && mode != CaptionMode.OFF) setPrompter(false)
        st.captionMode = mode
        captions.clear(); captionsVersion++
        if (mode == CaptionMode.OFF) { voice.stopContinuous(); st.notify("Phụ đề", "tắt", Colors.CYAN, 1.5); return }
        if (mode.translateTo != null && !st.llmReady) st.notify("Phiên dịch", "Cần LLM để dịch — tạm chỉ hiện phụ đề gốc", Colors.AMBER, 4.0)
        else st.notify(mode.title, "Đang nghe… (lần đầu tải model ${mode.lang})", Colors.CYAN, 2.5)
        voice.startContinuous(mode.lang,
            onPartial = { t -> captions.onPartial(t); captionsVersion++ },
            onFinal = { t ->
                val line = captions.onFinal(t); captionsVersion++
                val to = mode.translateTo
                if (line != null && to != null && st.llmReady) {
                    AiClient.translate(line.text, mode.lang, to) { r -> r.onSuccess { line.translation = it.trim(); captionsVersion++ } }
                }
            })
    }

    // ------------------------------------------------------------------ teleprompter
    override fun setPrompter(on: Boolean?) {
        val st = SpaceState
        val want = on ?: (prompter == null)
        if (!want) { prompter = null; prompterVersion++; voice.stopContinuous(); return }
        if (st.prompterScript.isBlank()) { st.notify("Nhắc chữ", "Dán kịch bản vào ô Teleprompter trong app trước", Colors.AMBER, 3.5); return }
        if (st.captionMode != CaptionMode.OFF) setCaptions(CaptionMode.OFF)
        val p = PrompterTracker(st.prompterScript)
        prompter = p; prompterVersion++
        if (!hasMic()) { st.notify("Nhắc chữ", "Không có micro: cuộn bằng phím / vuốt 2 ngón", Colors.AMBER, 3.0); return }
        // Vietnamese diacritics in the script -> Vietnamese model, else English
        val lang = if (st.prompterScript.any { it in "ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵ" }) "vi" else "en"
        voice.startContinuous(lang,
            onPartial = { t -> if (prompter === p && p.hear(t)) prompterVersion++ },
            onFinal = { t -> if (prompter === p && p.hear(t)) prompterVersion++ })
        st.notify("Nhắc chữ", "Cứ đọc, chữ tự chạy theo giọng bạn", Colors.CYAN, 2.5)
    }

    override fun replyNotif(text: String, key: String?) { PhoneNotifs.reply(app, text, key) }
    override fun setDesktopSite(on: Boolean) { SpaceHost.space?.setDesktopFocused(on) }

    /** Nod on a message pop-up: listen once, send what was said as the reply. */
    fun replyByVoice(key: String) {
        if (!hasMic()) { SpaceState.notify("Micro", "Cấp quyền micro trong app để trả lời bằng giọng", Colors.AMBER, 3.0); return }
        dictationSink = { text -> if (text.isNotBlank()) PhoneNotifs.reply(app, text, key) }
        SpaceState.notify("🎤 Nói câu trả lời", "Nói xong im lặng 1 giây là gửi", Colors.CYAN, 6.0)
        voice.captureCommand()
    }

    fun movePrompter(delta: Int) { prompter?.let { it.move(delta); prompterVersion++ } }

    const val MIRROR_URL = "mirror:phone"
}
