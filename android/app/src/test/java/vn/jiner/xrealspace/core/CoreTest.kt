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

    @Test fun mockRouteWalks() {
        val r = MockRoute()
        val s0 = r.state()
        assertEquals("Lê Lợi", s0.street); assertEquals("right", s0.turn)
        r.update(60.0)
        assertTrue(r.state().remain < s0.remain)
    }
}
