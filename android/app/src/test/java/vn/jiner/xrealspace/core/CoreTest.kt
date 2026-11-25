package vn.jiner.xrealspace.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class CoreTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------ protocol
    @Test fun imuStreamCommandMatchesPythonPrototype() {
        // python: b"\xAA" + crc32(len+msgid+data) LE + body, msgid 0x19 data [1]
        assertEquals("aac5d1214204001901", hex(Protocol.imuStream(true)))
    }

    @Test fun sampleRoundTripIncludingNegativeI24() {
        val g = Mat3.v(-12.5, 0.25, 300.0)
        val a = Mat3.v(-0.0625, 0.5, -0.984375)
        val s = Protocol.parseSample(Protocol.encodeSample(123456789012L, g, a))!!
        assertEquals(123456789012L, s.tsNs)
        for (i in 0..2) { assertEquals(g[i], s.gyroDps[i], 1e-3); assertEquals(a[i], s.accelG[i], 1e-3) }
        assertNull(Protocol.parseSample(ByteArray(64)))
    }

    // ------------------------------------------------------------ tracker
    private val up = Mat3.v(0.0, 0.18, 0.98).let { Mat3.unit(it) } // gravity direction in IMU frame (tilted)

    private fun feed(t: ImuTracker, startNs: Long, seconds: Double, gyro: (Double) -> V3, rnd: Random, accel: V3 = up): Long {
        var ts = startNs
        val n = (seconds * 1000).toInt()
        for (i in 0 until n) {
            val g = gyro(i / 1000.0)
            val noisy = DoubleArray(3) { g[it] + rnd.nextGaussian() * 0.1 }
            val an = DoubleArray(3) { accel[it] + rnd.nextGaussian() * 0.003 }
            t.update(ts, noisy, an)
            ts += 1_000_000L
        }
        return ts
    }

    @Test fun biasIgnoresMovingStartAndConverges() {
        val trueBias = Mat3.v(0.3, -0.2, 0.5)
        val rnd = Random(1)
        val t = ImuTracker()
        // first second: head moving (the Windows prototype would average this into the bias)
        var ts = feed(t, 0, 1.0, { s -> Mat3.add(trueBias, Mat3.v(0.0, 0.0, 12 * kotlin.math.sin(s * 9))) }, rnd)
        assertTrue(t.biasState != ImuTracker.BiasState.READY)
        ts = feed(t, ts, 1.5, { trueBias }, rnd)
        assertEquals(ImuTracker.BiasState.READY, t.biasState)
        for (i in 0..2) assertEquals(trueBias[i], t.bias[i], 0.15)   // first estimate
        feed(t, ts, 2.0, { trueBias }, rnd)                           // refinement while still
        for (i in 0..2) assertEquals(trueBias[i], t.bias[i], 0.03)
    }

    @Test fun stillDriftUnderThreeDegreesPerMinute() {
        val trueBias = Mat3.v(0.4, -0.3, 0.6)
        val rnd = Random(2)
        val t = ImuTracker()
        var ts = feed(t, 0, 2.0, { trueBias }, rnd)
        ts = feed(t, ts, 0.01, { trueBias }, rnd) // apply reset after calibration
        val r0 = t.orientation()
        feed(t, ts, 60.0, { trueBias }, rnd)
        val drift = Mat3.angleDeg(r0, t.orientation())
        assertTrue("drift $drift deg/min", drift < 3.0)
    }

    @Test fun wrongBiasIsCorrectedWhileStill() {
        // Even if the initial bias was poisoned by 2 deg/s, still periods must pull it back (prototype could not).
        val trueBias = Mat3.v(0.0, 0.0, 2.0)
        val rnd = Random(3)
        val t = ImuTracker()
        feed(t, 0, 1.5, { Mat3.v(0.0, 0.0, 0.0) }, rnd)      // calibrates to ~0
        assertEquals(0.0, t.bias[2], 0.05)
        feed(t, 1_500_000_000L, 5.0, { trueBias }, rnd)       // sensor bias shifts to 2 deg/s
        assertEquals(2.0, t.bias[2], 0.1)
    }

    @Test fun turn90DegreesIsMeasured() {
        val rnd = Random(4)
        val t = ImuTracker(kp = 0.0)
        var ts = feed(t, 0, 2.0, { Mat3.v(0.0, 0.0, 0.0) }, rnd)
        ts = feed(t, ts, 0.01, { Mat3.v(0.0, 0.0, 0.0) }, rnd)
        val r0 = t.orientation()
        ts = feed(t, ts, 1.0, { Mat3.scale(up, 90.0) }, rnd)   // 90 deg/s for 1 s about the gravity axis
        assertEquals(90.0, Mat3.angleDeg(r0, t.orientation()), 1.5)
        feed(t, ts, 1.0, { Mat3.scale(up, -90.0) }, rnd)       // back to centre
        assertTrue(Mat3.angleDeg(r0, t.orientation()) < 1.5)
    }

    @Test fun realAir2RecordingStartsMovingAndBiasStaysSmall() {
        val lines = javaClass.getResourceAsStream("/air2_start_4s.csv")!!.bufferedReader().readLines().drop(1)
        val t = ImuTracker()
        var naive = DoubleArray(3)
        lines.forEachIndexed { i, l ->
            val c = l.split(",").map { it.toDouble() }
            val g = Mat3.v(c[1], c[2], c[3]); val a = Mat3.v(c[4], c[5], c[6])
            if (i < 1000) for (k in 0..2) naive[k] += g[k] / 1000
            t.update(c[0].toLong(), g, a)
        }
        // the old "first 1000 samples" bias is ~6 deg/s on the yaw axis for this recording
        assertTrue("naive ${naive.toList()}", Mat3.norm(naive) > 5.0)
        assertEquals(ImuTracker.BiasState.READY, t.biasState)
        assertTrue("bias ${t.bias.toList()}", Mat3.norm(t.bias) < 1.5)
        assertTrue(t.rate > 900)
    }

    // ------------------------------------------------------------ projection
    @Test fun projectionCentresAndFollowsHead() {
        val C0 = Mat3.eye()
        val p = Projection.place(C0, Mat3.eye(), 2.0, 1.2, 1920, 1080, 23.5)
        assertTrue(p.visible); assertEquals(960.0, p.cx, 1e-6); assertEquals(540.0, p.cy, 1e-6)
        // left panel (yaw +40) is centred when the head turns left 40 deg
        val Cl = Mat3.rotY(40.0)
        val q = Projection.place(Cl, Mat3.rotY(40.0), 2.0, 1.2, 1920, 1080, 23.5)
        assertEquals(960.0, q.cx, 1e-6)
        // and the centre panel moves to the right of the screen
        assertTrue(Projection.place(Cl, Mat3.eye(), 2.0, 1.2, 1920, 1080, 23.5).cx > 1920)
        // behind the head: hidden
        assertFalse(Projection.place(Mat3.rotY(180.0), Mat3.eye(), 2.0, 1.2, 1920, 1080, 23.5).visible)
        // head roll -> panel counter-rotates by the same amount
        val roll = doubleArrayOf(Math.cos(0.2), -Math.sin(0.2), 0.0, Math.sin(0.2), Math.cos(0.2), 0.0, 0.0, 0.0, 1.0)
        assertEquals(Math.toDegrees(0.2), abs(Projection.place(roll, Mat3.eye(), 2.0, 1.2, 1920, 1080, 23.5).rollDeg), 1e-6)
    }

    @Test fun lazyFollowHoldsInsideDeadzone() {
        val f = LazyFollow(MapSpec(deadzoneDeg = 12.0))
        f.update(Mat3.eye(), 0.016)
        repeat(100) { f.update(Mat3.rotY(10.0), 0.016) }
        assertEquals(0.0, f.yaw!!, 1e-9)
        repeat(300) { f.update(Mat3.rotY(40.0), 0.016) }
        assertEquals(28.0, f.yaw!!, 0.5) // trails by the dead zone
    }

    // ------------------------------------------------------------ gestures / wizard / voice
    @Test fun nodAndShake() {
        val g = HeadGestures()
        val r = Math.toRadians(120.0)
        assertNull(g.update(Mat3.v(-r, 0.0, 0.0), 0.0))
        assertEquals(HeadGestures.Gesture.NOD, g.update(Mat3.v(r, 0.0, 0.0), 0.3))
        assertNull(g.update(Mat3.v(0.0, r, 0.0), 2.0))
        assertNull(g.update(Mat3.v(0.0, -r, 0.0), 2.2))
        assertEquals(HeadGestures.Gesture.SHAKE, g.update(Mat3.v(0.0, r, 0.0), 2.4))
    }

    @Test fun wizardRecoversAxisMapping() {
        val M = SpaceConfig.DEFAULT_IMU_TO_CAM
        val camX = Mat3.v(M[0], M[1], M[2]); val camY = Mat3.v(M[3], M[4], M[5])
        val t = ImuTracker()
        val rnd = Random(5)
        val w = AxisWizard(t, 0.0)
        var ts = 0L
        var now = 0.0
        var result: M3? = null
        // a scripted user: still 1.5 s, then follows the on-screen prompts
        while (now < 30 && result == null) {
            val txt = w.text
            val gyro = when {
                txt.contains("QUAY TRÁI NGAY") && now % 2 < 0.5 -> Mat3.scale(camY, 90.0)
                txt.contains("CÚI XUỐNG NGAY") && now % 2 < 0.5 -> Mat3.scale(camX, -60.0)
                else -> Mat3.v(0.0, 0.0, 0.0)
            }
            for (k in 0 until 16) { t.update(ts, DoubleArray(3) { gyro[it] + rnd.nextGaussian() * 0.1 }, camY); ts += 1_000_000 }
            now += 0.016
            result = w.update(now)
        }
        assertNotNull(result)
        for (i in 0 until 9) assertEquals("i=$i", M[i], result!![i], 0.05)
    }

    @Test fun voiceCommands() {
        assertEquals(VoiceCommands.Cmd.Open(null, "https://m.youtube.com"), VoiceCommands.parse("Mở YouTube"))
        assertEquals(VoiceCommands.Cmd.Map(false), VoiceCommands.parse("tắt bản đồ"))
        assertEquals(VoiceCommands.Cmd.Map(true), VoiceCommands.parse("bật bản đồ"))
        assertEquals(VoiceCommands.Cmd.Recenter, VoiceCommands.parse("đưa màn hình về giữa"))
        assertEquals(VoiceCommands.Cmd.Walk(true), VoiceCommands.parse("chế độ đi bộ"))
        assertEquals(VoiceCommands.Cmd.Summarize, VoiceCommands.parse("tóm tắt trang này"))
        assertTrue(VoiceCommands.parse("thủ đô của Pháp là gì") is VoiceCommands.Cmd.Ask)
        assertTrue(VoiceCommands.parse("hello world") is VoiceCommands.Cmd.Unknown)
    }

    @Test fun nineSlotGrid() {
        assertEquals(5, SlotGrid.parseSlot(VoiceCommands.fold("ô 5")))
        assertEquals(3, SlotGrid.parseSlot(VoiceCommands.fold("đặt vào ô ba")))
        assertEquals(9, SlotGrid.parseSlot(VoiceCommands.fold("slot nine")))
        assertEquals(7, SlotGrid.parseSlot(VoiceCommands.fold("màn số 7")))
        assertNull(SlotGrid.parseSlot(VoiceCommands.fold("ở đâu")))
        assertEquals(VoiceCommands.Cmd.Place(3, "https://m.youtube.com", null), VoiceCommands.parse("YouTube ô 3"))
        assertEquals(VoiceCommands.Cmd.Place(5, "https://mail.google.com", null), VoiceCommands.parse("đặt Gmail vào ô năm"))
        val q = VoiceCommands.parse("tìm thời tiết Sài Gòn ô 6") as VoiceCommands.Cmd.Place
        assertEquals(6, q.slot); assertTrue(q.url, q.url.contains("thoi+tiet+sai+gon"))
        assertEquals(VoiceCommands.Cmd.ClearSlot(7), VoiceCommands.parse("xoá ô 7"))
        assertEquals(VoiceCommands.Cmd.ShowGrid, VoiceCommands.parse("hiện lưới"))
        assertEquals(VoiceCommands.Cmd.SaveWorkspace("code"), VoiceCommands.parse("lưu không gian code"))
        assertEquals(VoiceCommands.Cmd.Workspace("code"), VoiceCommands.parse("mở không gian code"))
        assertEquals(VoiceCommands.Cmd.Workspace("giai tri"), VoiceCommands.parse("chế độ rạp phim"))
        // layout geometry: keypad order, 5 = straight ahead
        assertEquals(0.0, SlotGrid.yaw(5), 0.0); assertEquals(0.0, SlotGrid.pitch(5), 0.0)
        assertTrue(SlotGrid.yaw(1) > 0 && SlotGrid.pitch(1) > 0)   // top-left
        assertTrue(SlotGrid.yaw(9) < 0 && SlotGrid.pitch(9) < 0)   // bottom-right
        assertEquals(4, SlotGrid.nearest(40.0, 2.0))
        assertEquals(6, SlotGrid.slotOf(SlotGrid.spec(6, "x", "y")))
        assertEquals("Code", Workspaces.find("code")!!.title)
        val saved = mapOf("doc bao" to Workspaces.Workspace("doc bao", "Đọc báo", emptyList(), 2.0, false))
        assertEquals("Đọc báo", Workspaces.find("đọc báo", saved)!!.title)
    }

    @Test fun wakeWord() {
        assertTrue(WakeWord.matches("kính ơi", WakeWord.DEFAULT_PHRASES))
        assertFalse(WakeWord.matches("[unk]", WakeWord.DEFAULT_PHRASES))
        assertEquals("mở youtube ô 3", WakeWord.remainder("kính ơi mở youtube ô 3", WakeWord.DEFAULT_PHRASES))
        assertEquals("""["kính ơi","[unk]"]""", WakeWord.voskGrammar(listOf("Kính ơi")))
    }

    @Test fun llmReplyParsing() {
        val text = """Sure! ```json
            {"actions":[{"fn":"place","slot":3,"url":"https://m.youtube.com","title":"YouTube"},
                        {"fn":"map","on":false},{"fn":"search","query":"giá vàng","slot":6},{"fn":"answer","text":"Paris \"là\" thủ đô"}],
             "say":"Đã đặt YouTube ở ô 3"}```"""
        val r = LlmProtocol.parseReply(text)
        assertEquals("Đã đặt YouTube ở ô 3", r.say)
        assertEquals(VoiceCommands.Cmd.Place(3, "https://m.youtube.com", "YouTube"), r.cmds[0])
        assertEquals(VoiceCommands.Cmd.Map(false), r.cmds[1])
        assertEquals(6, (r.cmds[2] as VoiceCommands.Cmd.Place).slot)
        assertEquals(VoiceCommands.Cmd.Answer("Paris \"là\" thủ đô"), r.cmds[3])
        // plain prose -> shown as an answer
        assertTrue(LlmProtocol.parseReply("Xin chào") .cmds[0] is VoiceCommands.Cmd.Answer)
        // request bodies are valid JSON and keep unicode
        val body = MiniJson.parse(LlmProtocol.anthropicBody("m", "sys \"q\"\n", "mở ô 3")) as Map<*, *>
        assertEquals("mở ô 3", ((body["messages"] as List<*>)[0] as Map<*, *>)["content"])
        assertEquals("hi", LlmProtocol.responseText("""{"content":[{"type":"text","text":"hi"}]}"""))
        assertEquals("yo", LlmProtocol.responseText("""{"choices":[{"message":{"role":"assistant","content":"yo"}}]}"""))
    }

    @Test fun jevDecisionToCommand() {
        val body = MiniJson.parse(JevProtocol.requestBody("typesafe/jev-1.13", "YouTube ô 3")) as Map<*, *>
        val qs = body["questions"] as Map<*, *>
        assertEquals("choice", (qs["intent"] as Map<*, *>)["type"])
        assertTrue(((qs["intent"] as Map<*, *>)["criteria"] as Map<*, *>).containsKey("place"))
        assertEquals("YouTube ô 3", (body["state"] as Map<*, *>)["utterance"])
        // response shape from the OpenRouter Decisions API docs
        val resp = """{"id":"gen-dec-1","model":"typesafe/jev-1.13-20260917","answers":{
            "intent":{"type":"choice","choice":"place","confidence":0.82,"probabilities":{"place":0.9,"open":0.1}},
            "site":{"type":"choice","choice":"youtube","confidence":0.9},
            "switch":{"type":"choice","choice":"toggle","confidence":0.5}},"usage":{"input_tokens":476}}"""
        val d = JevProtocol.parseDecision(resp)!!
        assertEquals("place", d.intent); assertEquals(0.82, d.confidence, 1e-9); assertEquals("youtube", d.site)
        assertEquals(VoiceCommands.Cmd.Place(3, "https://m.youtube.com", "youtube"), JevProtocol.toCmd(d, "YouTube ô 3"))
        assertEquals(VoiceCommands.Cmd.Map(false), JevProtocol.toCmd(JevProtocol.Decision("map", 0.9, "other", "off"), "tắt bản đồ"))
        assertTrue(JevProtocol.toCmd(JevProtocol.Decision("place", 0.2, null, null), "x") is VoiceCommands.Cmd.Unknown)
        val s = JevProtocol.toCmd(JevProtocol.Decision("search", 0.9, "other", null), "tìm giá vàng hôm nay") as VoiceCommands.Cmd.Open
        assertTrue(s.url, s.url.endsWith("q=gia+vang+hom+nay"))
    }

    @Test fun followModes() {
        val b = BodyFollow()
        assertEquals(0.0, Projection.yawPitch(b.update(Mat3.rotY(30.0), FollowMode.WORLD, 0.016)).first, 1e-9)
        // travel: a 15 deg turn stays inside the dead zone, a 90 deg turn (bus turning a corner) is followed
        b.update(Mat3.eye(), FollowMode.TRAVEL, 0.016)
        repeat(200) { b.update(Mat3.rotY(15.0), FollowMode.TRAVEL, 0.016) }
        assertEquals(0.0, b.yaw, 1e-9)
        repeat(600) { b.update(Mat3.rotY(90.0), FollowMode.TRAVEL, 0.016) }
        assertEquals(90.0 - FollowMode.TRAVEL.deadzoneDeg, b.yaw, 0.5)
        // cinema: lying back 40 deg -> the screen comes up to within the dead zone
        val c = BodyFollow()
        c.update(Mat3.eye(), FollowMode.CINEMA, 0.016)
        repeat(1500) { c.update(Mat3.rotX(40.0), FollowMode.CINEMA, 0.016) }
        assertEquals(40.0 - FollowMode.CINEMA.deadzoneDeg * 0.7, c.pitch, 0.5)
        assertEquals(FollowMode.CINEMA, FollowMode.WORLD.next())
        assertEquals(VoiceCommands.Cmd.Follow(FollowMode.TRAVEL), VoiceCommands.parse("chế độ di chuyển"))
        assertEquals(VoiceCommands.Cmd.Follow(FollowMode.WORLD), VoiceCommands.parse("cố định màn hình"))
        assertEquals(VoiceCommands.Cmd.DarkWeb(true), VoiceCommands.parse("bật web tối"))
    }

    @Test fun rulesFirstFastPath() {
        assertTrue(FastPath.accept("YouTube ô 3", VoiceCommands.parse("YouTube ô 3")))
        assertTrue(FastPath.accept("về giữa", VoiceCommands.parse("về giữa")))
        assertFalse(FastPath.accept("mở youtube ô 3 rồi tắt bản đồ", VoiceCommands.parse("mở youtube ô 3 rồi tắt bản đồ")))
        assertFalse(FastPath.accept("thủ đô Pháp là gì", VoiceCommands.parse("thủ đô Pháp là gì")))
        assertFalse(FastPath.accept("tìm quán phở gần đây", VoiceCommands.parse("tìm quán phở gần đây")))
    }

    @Test fun teleprompterFollowsVoice() {
        val p = PrompterTracker("Xin chào các bạn, hôm nay mình giới thiệu kính XREAL Air 2 với app tự viết. Đầu tiên là lưới chín ô.")
        assertEquals(0, p.pos)
        assertTrue(p.hear("xin chào các bạn"))
        assertEquals(4, p.pos)                                  // after "bạn,"
        assertFalse(p.hear("ờ ừm"))                             // filler does not move
        assertTrue(p.hear("hôm nay mình giới thiệu kính"))
        assertEquals("kính", p.words[p.pos - 1])
        assertTrue(p.hear("đầu tiên là lưới"))                  // skipped a sentence: jumps ahead inside the window
        assertEquals("lưới", p.words[p.pos - 1])
        p.move(-100); assertEquals(0, p.pos)
    }

    @Test fun captionLines() {
        val b = CaptionBuffer(maxLines = 2, maxChars = 30)
        b.onPartial("hello every")
        assertEquals("hello every", b.visible().last().text)
        b.onFinal("hello everyone")
        b.onFinal("this is a much longer sentence that must be split across lines")
        val v = b.visible()
        assertEquals(2, v.size)
        assertTrue(v.all { it.text.length <= 30 })
        assertTrue(v.joinToString(" ") { it.text }.endsWith("split across lines"))
    }

    @Test fun mirrorAndApps() {
        assertEquals(540f to 1200f, MirrorMap.toPhone(270f, 600f, 540f, 1200f, 1080, 2400)!!.let { it.first to it.second })
        assertNull(MirrorMap.toPhone(-1f, 5f, 540f, 1200f, 1080, 2400))
        val apps = listOf(AppMatcher.App("Zalo", "com.zing.zalo"), AppMatcher.App("Grab", "com.grabtaxi.passenger"),
            AppMatcher.App("Google Maps", "com.google.android.apps.maps"), AppMatcher.App("Shopee", "com.shopee.vn"))
        assertEquals("com.zing.zalo", AppMatcher.find("zalo", apps)!!.pkg)
        assertEquals("com.google.android.apps.maps", AppMatcher.find("google maps", apps)!!.pkg)
        assertNull(AppMatcher.find("telegram", apps))
        assertEquals(VoiceCommands.Cmd.LaunchApp("zalo"), VoiceCommands.parse("mở Zalo"))
        assertEquals(VoiceCommands.Cmd.LaunchApp("grab"), VoiceCommands.parse("mở ứng dụng Grab lên"))
        assertEquals(VoiceCommands.Cmd.Open(null, "https://m.youtube.com"), VoiceCommands.parse("mở YouTube"))
        assertEquals(VoiceCommands.Cmd.Mirror(4), VoiceCommands.parse("màn hình điện thoại ô 4"))
        assertEquals(VoiceCommands.Cmd.MirrorOff, VoiceCommands.parse("tắt màn hình điện thoại"))
        assertEquals(VoiceCommands.Cmd.Captions(CaptionMode.EN_TO_VI), VoiceCommands.parse("dịch tiếng Anh"))
        assertEquals(VoiceCommands.Cmd.Captions(CaptionMode.VI), VoiceCommands.parse("bật phụ đề"))
        assertEquals(VoiceCommands.Cmd.Captions(CaptionMode.OFF), VoiceCommands.parse("tắt phụ đề"))
        assertEquals(VoiceCommands.Cmd.Prompter(true), VoiceCommands.parse("bật nhắc chữ"))
        assertEquals(VoiceCommands.Cmd.Back, VoiceCommands.parse("quay lại"))
        val r = LlmProtocol.parseReply("""{"actions":[{"fn":"open_app","name":"Zalo"},{"fn":"mirror","slot":4},{"fn":"captions","mode":"en_vi"}]}""")
        assertEquals(listOf(VoiceCommands.Cmd.LaunchApp("Zalo"), VoiceCommands.Cmd.Mirror(4), VoiceCommands.Cmd.Captions(CaptionMode.EN_TO_VI)), r.cmds)
    }

    @Test fun mockRouteWalks() {
        val r = MockRoute()
        val s0 = r.state()
        assertEquals("Lê Lợi", s0.street); assertEquals("right", s0.turn)
        r.update(60.0)
        assertTrue(r.state().remain < s0.remain)
    }

    @Test fun remoteProtocolRoundTrips() {
        val msgs = listOf(
            RemoteProtocol.Msg.Hello("Pixel 7"), RemoteProtocol.Msg.Hello("Pixel 7", 1, "0420"),
            RemoteProtocol.Msg.Auth(true), RemoteProtocol.Msg.Auth(false, "Sai mã | thử lại"), RemoteProtocol.Msg.Pad(2, 12.5f, -3f), RemoteProtocol.Msg.TwoTap,
            RemoteProtocol.Msg.Swipe2(-80f, 4f), RemoteProtocol.Msg.Say("mở youtube | ô 5"), RemoteProtocol.Msg.Text(""),
            RemoteProtocol.Msg.Text("xin chào\nbạn"), RemoteProtocol.Msg.Enter, RemoteProtocol.Msg.Key("click"),
            RemoteProtocol.Msg.Focus("a|b"), RemoteProtocol.Msg.Blur, RemoteProtocol.Msg.Ping)
        for (m in msgs) {
            val line = RemoteProtocol.encode(m)
            assertFalse(line.contains('\n'))
            assertEquals(m, RemoteProtocol.decode(line))
        }
        assertNull(RemoteProtocol.decode("PAD|x"))
        assertNull(RemoteProtocol.decode("WHAT"))
        assertTrue(RemoteProtocol.isLanAddress("192.168.1.23"))
        assertTrue(RemoteProtocol.isLanAddress("10.0.0.5"))
        assertFalse(RemoteProtocol.isLanAddress("8.8.8.8"))
        assertFalse(RemoteProtocol.isLanAddress("evil.com"))
    }

    @Test fun pairingPinAndLockout() {
        val pin = RemoteProtocol.newPin(Random(7))
        assertEquals(4, pin.length); assertTrue(pin.all { it.isDigit() })
        val g = PairingGuard("1234", maxFails = 3, lockMs = 60_000)
        assertEquals(PairingGuard.Result.OK, g.check(" 1234 ", 0))
        assertEquals(PairingGuard.Result.WRONG, g.check("0000", 1))
        assertEquals(PairingGuard.Result.WRONG, g.check("123", 2))
        assertEquals(PairingGuard.Result.LOCKED, g.check("12345", 3))      // 3rd wrong -> locked
        assertEquals(PairingGuard.Result.LOCKED, g.check("1234", 10_000)) // even the right PIN, while locked
        assertEquals(51L, g.secondsLocked(10_000))
        assertEquals(PairingGuard.Result.OK, g.check("1234", 60_004))
        assertEquals(PairingGuard.Result.WRONG, PairingGuard("").check("", 0)) // no PIN set = nobody gets in
        // old phones (v0.5) send HELLO without a PIN -> decoded as empty PIN, rejected
        assertEquals(RemoteProtocol.Msg.Hello("A", 1, ""), RemoteProtocol.decode("HELLO|A|1"))
    }

    @Test fun notificationFeedFiltersDedupesAndMutes() {
        val f = NotifFeed("vn.jiner.xrealspace")
        fun n(key: String, pkg: String, app: String, text: String, reply: Boolean = true) = PhoneNotif(key, pkg, app, "An", text, reply, 0)
        assertTrue(f.add(n("k1", "com.zing.zalo", "Zalo", "Tối nay đi ăn không?")))
        assertFalse(f.add(n("k1", "com.zing.zalo", "Zalo", "Tối nay đi ăn không?")))   // re-posted, same text
        assertTrue(f.add(n("k1", "com.zing.zalo", "Zalo", "7h nhé")))                  // same chat, new message
        assertEquals(1, f.size())
        assertFalse(f.add(n("x", "vn.jiner.xrealspace", "XREAL Space", "own")))      // our own foreground notification
        assertFalse(f.add(n("o", "com.spotify.music", "Spotify", "Now playing"), ongoing = true))
        assertFalse(f.add(PhoneNotif("e", "com.foo", "Foo", "", "", false, 0)))           // empty title+text is dropped
        assertEquals("com.zing.zalo", f.muteByName("zalo", true))
        assertFalse(f.add(n("k2", "com.zing.zalo", "Zalo", "muted but stored")))
        assertEquals(2, f.size())
        f.muteByName("Zalo", false); f.doNotDisturb = true
        assertFalse(f.add(n("k3", "com.facebook.orca", "Messenger", "dnd", reply = false)))
        assertEquals("k2", f.latestRepliable()?.key)
        val (title, body) = NotifFeed.preview(f.latest()!!, moving = true)
        assertEquals("Messenger", title); assertFalse(body.contains("dnd"))         // no message text while riding
        assertTrue(NotifFeed.readout(f.recent()).startsWith("• Messenger: An"))
    }

    @Test fun speedGuardHasHysteresis() {
        val g = SpeedGuard()
        assertFalse(g.update(6.0, 0)); assertFalse(g.update(6.0, 2000))   // 21.6 km/h but not for 3 s yet
        assertFalse(g.update(2.0, 2500))                                    // dropped below: timer resets
        assertFalse(g.update(6.0, 3000)); assertTrue(g.update(6.0, 6100)); assertTrue(g.fast)
        assertFalse(g.update(3.0, 7000))                                    // 10.8 km/h: between thresholds, stays fast
        assertFalse(g.update(1.0, 8000)); assertTrue(g.update(1.0, 14100)); assertFalse(g.fast)
        g.update(6.0, 20000); g.update(6.0, 23100); assertTrue(g.fast)
        assertTrue(g.lost()); assertFalse(g.fast)                           // GPS lost -> screens come back
    }

    @Test fun notificationVoiceCommands() {
        assertEquals(VoiceCommands.Cmd.ReadNotifs, VoiceCommands.parse("đọc thông báo"))
        assertEquals(VoiceCommands.Cmd.ReadNotifs, VoiceCommands.parse("thông báo"))
        assertEquals(VoiceCommands.Cmd.Reply("Ok 7h nhé"), VoiceCommands.parse("trả lời Ok 7h nhé"))
        assertEquals(VoiceCommands.Cmd.MuteNotifs(true, "zalo"), VoiceCommands.parse("tắt thông báo Zalo"))
        assertEquals(VoiceCommands.Cmd.MuteNotifs(true, null), VoiceCommands.parse("im lặng"))
        assertEquals(VoiceCommands.Cmd.MuteNotifs(false, null), VoiceCommands.parse("bật thông báo"))
        assertEquals(VoiceCommands.Cmd.Notify, VoiceCommands.parse("thông báo mẫu"))
        assertEquals(VoiceCommands.Cmd.Map(false), VoiceCommands.parse("tắt bản đồ"))           // old rules unaffected
        val r = LlmProtocol.parseReply("""{"actions":[{"fn":"reply","text":"đang bận"},{"fn":"mute_notifications","on":true,"app":null}]}""")
        assertEquals(listOf(VoiceCommands.Cmd.Reply("đang bận"), VoiceCommands.Cmd.MuteNotifs(true, null)), r.cmds)
    }

    // ---------------------------------------------------------------- 0.7: GPS navigation, 3D, films
    private val osrmJson = """{"code":"Ok","routes":[{"distance":420.5,"duration":95.0,
      "geometry":{"type":"LineString","coordinates":[[106.7000,10.7700],[106.7000,10.7720],[106.7020,10.7720]]},
      "legs":[{"steps":[
        {"distance":222.4,"name":"Lê Lợi","maneuver":{"type":"depart","modifier":"","location":[106.7000,10.7700]}},
        {"distance":218.6,"name":"Nguyễn Huệ","maneuver":{"type":"turn","modifier":"right","location":[106.7000,10.7720]}},
        {"distance":0,"name":"","maneuver":{"type":"arrive","modifier":"","location":[106.7020,10.7720]}}]}]}]}"""

    @Test fun geoMath() {
        val a = LatLon(10.7700, 106.7000); val b = LatLon(10.7720, 106.7000)
        assertEquals(222.4, Geo.distance(a, b), 0.5)                       // 0.002° lat ≈ 222 m
        assertEquals(0.0, Geo.bearing(a, b), 0.01)                          // due north
        assertEquals(90.0, Geo.bearing(b, LatLon(10.7720, 106.7020)), 0.1)  // due east
        val (x, y) = Geo.toLocal(a, b); assertEquals(0.0, x, 0.01); assertEquals(222.4, y, 0.5)
        val (px, py) = Geo.worldPx(LatLon(0.0, 0.0), 1); assertEquals(256.0, px, 1e-6); assertEquals(256.0, py, 1e-6)
        assertEquals(1, Geo.tileOf(300.0))
    }

    @Test fun osmParsersAndTurnText() {
        val r = OsmApi.parseRoute(osrmJson)!!
        assertEquals(3, r.points.size); assertEquals(3, r.steps.size); assertEquals(420.5, r.distanceM, 1e-9)
        assertEquals("Rẽ phải vào Nguyễn Huệ", r.steps[1].text()); assertEquals("right", r.steps[1].arrow())
        assertEquals("Đến nơi", r.steps[2].text())
        assertNull(OsmApi.parseRoute("""{"code":"NoRoute","routes":[]}"""))
        val p = OsmApi.parsePlace("""[{"lat":"10.7725","lon":"106.6980","name":"Chợ Bến Thành","display_name":"Chợ Bến Thành, Quận 1"}]""")!!
        assertEquals("Chợ Bến Thành", p.name); assertEquals(10.7725, p.at.lat, 1e-9)
        assertNull(OsmApi.parsePlace("[]"))
        assertTrue(OsmApi.searchUrl("bến thành", null).contains("q=b%E1%BA%BFn+th%C3%A0nh"))
        assertTrue(OsmApi.routeUrl(LatLon(1.0, 2.0), LatLon(3.0, 4.0), "foot").contains("routed-foot/route/v1/driving/2.0,1.0;4.0,3.0"))
    }

    @Test fun navTrackerFollowsRoute() {
        val nav = NavTracker(OsmApi.parseRoute(osrmJson)!!, "Nguyễn Huệ")
        val s1 = nav.update(LatLon(10.7710, 106.70003))                    // halfway up the first street, 3 m off
        assertEquals("right", s1.next?.arrow()); assertEquals(111.0, s1.toNextM, 3.0)
        assertTrue(s1.offRouteM < 5); assertFalse(s1.arrived); assertEquals(330.0, s1.remainM, 5.0)
        val s2 = nav.update(LatLon(10.7721, 106.7010))                     // after the turn
        assertEquals("Đến nơi", s2.next?.text())
        assertTrue(nav.update(LatLon(10.7745, 106.7000)).offRouteM > 200)   // wandered off
        assertTrue(nav.update(LatLon(10.7720, 106.70195)).arrived)
        assertEquals("1.2 km", NavFmt.dist(1234.0)); assertEquals("340 m", NavFmt.dist(345.0))
    }

    @Test fun parseNavigationAndFilmCommands() {
        assertEquals(VoiceCommands.Cmd.Navigate("Chợ Bến Thành", "car"), VoiceCommands.parse("chỉ đường tới Chợ Bến Thành"))
        assertEquals(VoiceCommands.Cmd.Navigate("Highlands Nguyễn Huệ", "foot"), VoiceCommands.parse("đi bộ đến Highlands Nguyễn Huệ"))
        assertEquals(VoiceCommands.Cmd.Navigate("the airport", "car"), VoiceCommands.parse("navigate to the airport"))
        assertEquals(VoiceCommands.Cmd.StopNav, VoiceCommands.parse("dừng chỉ đường"))
        assertEquals(VoiceCommands.Cmd.AdBlock(false), VoiceCommands.parse("tắt chặn quảng cáo"))
        assertEquals(VoiceCommands.Cmd.AdBlock(true), VoiceCommands.parse("chặn quảng cáo"))
        assertEquals(VoiceCommands.Cmd.DesktopSite(true), VoiceCommands.parse("bản máy tính"))
        assertEquals(VoiceCommands.Cmd.Map(true), VoiceCommands.parse("mở bản đồ"))                   // unchanged
    }

    @Test fun stereoAdsAndResume() {
        assertTrue(Stereo.isSbs(3840, 1080)); assertFalse(Stereo.isSbs(1920, 1080))
        val f = 1000.0
        assertEquals(0f, Stereo.shiftPx(4.0, f), 1e-4f)                     // at the optics' distance: no shift
        assertTrue(Stereo.shiftPx(1.5, f) > 0f)                             // nearer: pops out
        assertTrue(Stereo.shiftPx(1.0, f) > Stereo.shiftPx(2.0, f))
        assertTrue(AdFilter.isAd("pagead2.googlesyndication.com")); assertTrue(AdFilter.isAd("POPADS.NET"))
        assertFalse(AdFilter.isAd("youtube.com")); assertFalse(AdFilter.isAd("notdoubleclick.net"))
        assertTrue(AdFilter.isHijack("phim.example.com", "win-prize.xyz", hasGesture = false, isRedirect = false))
        assertFalse(AdFilter.isHijack("phim.example.com", "cdn.example.com", false, false))       // same site
        assertFalse(AdFilter.isHijack("phim.example.com", "accounts.google.com", false, true))    // server redirect
        assertFalse(AdFilter.isHijack("phim.example.com", "other.com", true, false))              // user tapped
        assertEquals("bbc.co.uk", AdFilter.site("www.bbc.co.uk"))
        val r = ResumeStore()
        r.save("https://phim.x/ep1?utm_source=fb", 754.0, 2700.0); assertEquals(754.0, r.get("https://phim.x/ep1"), 1e-9)
        r.save("https://phim.x/ep1", 12.0, 2700.0); assertEquals(0.0, r.get("https://phim.x/ep1"), 1e-9)   // restarted -> forget
        r.save("https://phim.x/ep2", 2690.0, 2700.0); assertEquals(0.0, r.get("https://phim.x/ep2"), 1e-9) // finished
        assertEquals("12:34", ResumeStore.clock(754.0)); assertEquals("1:02:03", ResumeStore.clock(3723.0))
    }
}
