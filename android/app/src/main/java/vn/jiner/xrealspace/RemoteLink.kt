package vn.jiner.xrealspace

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import vn.jiner.xrealspace.core.RemoteProtocol
import vn.jiner.xrealspace.core.RemoteProtocol.Msg
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Phone <-> glasses box over the local Wi-Fi, for devices without a touchscreen (the original XREAL Beam):
 *  - HOST (the box driving the glasses): TCP server on port 47800, announced with NSD/mDNS as "_xspace._tcp".
 *  - REMOTE (your phone): finds the host (or you type its IP), then its touchpad, microphone (speech-to-text on
 *    the phone) and keyboard drive the host. One remote at a time. LAN only, no internet relay.
 */
object RemoteLink {
    enum class Role { OFF, HOST, REMOTE }

    @Volatile var role = Role.OFF; private set
    @Volatile var status = "tắt"; private set
    @Volatile var peerName = ""; private set
    val connected get() = writer != null

    /** REMOTE side: the host asked to open / close the phone keyboard */
    var onFocus: ((String) -> Unit)? = null
    var onBlur: (() -> Unit)? = null
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val sendQ = Executors.newSingleThreadExecutor()
    private var server: ServerSocket? = null
    private var socket: Socket? = null
    @Volatile private var writer: PrintWriter? = null
    private var nsd: NsdManager? = null
    private var regListener: NsdManager.RegistrationListener? = null
    private var discListener: NsdManager.DiscoveryListener? = null

    private fun set(s: String) { status = s; SpaceState.log("remote: $s"); main.post { onChange?.invoke() } }

    fun send(m: Msg) {
        val w = writer ?: return
        sendQ.execute { try { w.println(RemoteProtocol.encode(m)); if (w.checkError()) closeSocket("mất kết nối") } catch (_: Exception) {} }
    }

    // ------------------------------------------------------------------ HOST
    fun startHost(ctx: Context) {
        if (role == Role.HOST) return
        stop()
        role = Role.HOST
        io.execute {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(RemoteProtocol.PORT))
                server = ss
                set("chờ điện thoại kết nối (cổng ${RemoteProtocol.PORT})")
                main.post { announce(ctx) }
                while (role == Role.HOST && !ss.isClosed) {
                    val s = ss.accept()
                    closeSocket(null)                       // one remote at a time: the newest wins
                    attach(s)
                }
            } catch (e: Exception) { if (role == Role.HOST) set("lỗi máy chủ: ${e.message}") }
        }
        // page text fields -> the remote phone keyboard
        SpaceState.remoteFocusSink = { v -> if (connected) { send(Msg.Focus(v)); true } else false }
        SpaceState.remoteBlurSink = { if (connected) { send(Msg.Blur); true } else false }
    }

    private fun announce(ctx: Context) {
        val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        val info = NsdServiceInfo().apply {
            serviceName = "XREAL Space ${Build.MODEL}"; serviceType = RemoteProtocol.SERVICE_TYPE; port = RemoteProtocol.PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { SpaceState.log("NSD registered ${i.serviceName}") }
            override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { SpaceState.log("NSD register failed $code") }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) {}
        }
        regListener = l
        try { m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { SpaceState.log("NSD: $e") }
    }

    /** HOST: apply a message from the phone. Runs on the main thread. */
    private fun apply(m: Msg) {
        val st = SpaceState
        val space = SpaceHost.space
        when (m) {
            is Msg.Hello -> { peerName = m.name; set("đã nối với ${m.name}"); st.notify("Điều khiển từ xa", "Đã nối với ${m.name}", Colors.GREEN, 2.0) }
            is Msg.Pad -> space?.touchpad(m.action, m.dx, m.dy)
            Msg.TwoTap -> st.recenter()
            Msg.ThreeTap -> st.setFollow(st.followMode.next())
            is Msg.Swipe2 -> {
                if (Assistant.prompter != null && kotlin.math.abs(m.dx) >= kotlin.math.abs(m.dy)) Assistant.movePrompter(if (m.dx < 0) 8 else -8)
                else if (kotlin.math.abs(m.dx) >= kotlin.math.abs(m.dy)) { if (m.dx < 0) space?.back() else space?.forward() }
                else if (m.dy < 0) st.showGrid(6.0) else st.toggleWalk()
            }
            is Msg.Say -> Assistant.handleUtterance(m.text)
            is Msg.Text -> space?.setFocusedInputValue(m.value)
            Msg.Enter -> space?.typeText("", true)
            is Msg.Key -> when (m.name) { "click" -> space?.clickAtGaze(); "recenter" -> st.recenter() }
            Msg.Ping -> {}
            else -> {}
        }
    }

    // ------------------------------------------------------------------ REMOTE
    /** Find a host on the Wi-Fi with NSD; connects to the first one found. */
    fun discoverAndConnect(ctx: Context) {
        stop()
        role = Role.REMOTE
        val m = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsd = m
        set("đang tìm máy kính trong Wi-Fi…")
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceType.startsWith("_xspace")) return
                try { m.stopServiceDiscovery(this) } catch (_: Exception) {}
                discListener = null
                @Suppress("DEPRECATION")
                m.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(r: NsdServiceInfo) {
                        @Suppress("DEPRECATION") val host = r.host?.hostAddress ?: return
                        connect(host, r.port)
                    }
                    override fun onResolveFailed(r: NsdServiceInfo, code: Int) { set("không phân giải được máy kính ($code)") }
                })
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, code: Int) { set("không tìm được ($code) — nhập IP") }
            override fun onStopDiscoveryFailed(t: String, code: Int) {}
        }
        discListener = l
        try { m.discoverServices(RemoteProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { set("NSD lỗi: ${e.message}") }
    }

    /** REMOTE: connect to a host by IP typed by the user (LAN addresses only). */
    fun connect(host: String, port: Int = RemoteProtocol.PORT) {
        if (!RemoteProtocol.isLanAddress(host)) { set("chỉ nối IP trong mạng nội bộ (192.168.x.x, 10.x.x.x…)"); return }
        role = Role.REMOTE
        set("đang nối $host…")
        io.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                attach(s)
                send(Msg.Hello(Build.MODEL))
                set("đã nối $host")
                // speech recognized on this phone goes to the glasses box
                Assistant.remoteSink = { text -> send(Msg.Say(text)) }
            } catch (e: Exception) { set("không nối được $host: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ shared
    private fun attach(s: Socket) {
        socket = s
        writer = PrintWriter(s.getOutputStream().bufferedWriter(Charsets.UTF_8), true)
        main.post { onChange?.invoke() }
        io.execute {
            try {
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                while (true) {
                    val line = r.readLine() ?: break
                    val m = RemoteProtocol.decode(line) ?: continue
                    main.post {
                        if (role == Role.HOST) apply(m)
                        else when (m) {
                            is Msg.Focus -> onFocus?.invoke(m.value)
                            Msg.Blur -> onBlur?.invoke()
                            is Msg.Status -> peerName = m.text
                            else -> {}
                        }
                    }
                }
            } catch (_: Exception) {}
            if (socket === s) closeSocket(if (role == Role.HOST) "điện thoại đã ngắt — chờ kết nối lại" else "mất kết nối")
        }
    }

    private fun closeSocket(reason: String?) {
        try { socket?.close() } catch (_: Exception) {}
        socket = null; writer = null
        if (reason != null) set(reason)
        main.post { onChange?.invoke() }
    }

    fun stop() {
        role = Role.OFF
        closeSocket(null)
        try { server?.close() } catch (_: Exception) {}
        server = null
        nsd?.let { m ->
            regListener?.let { try { m.unregisterService(it) } catch (_: Exception) {} }
            discListener?.let { try { m.stopServiceDiscovery(it) } catch (_: Exception) {} }
        }
        regListener = null; discListener = null
        Assistant.remoteSink = null
        SpaceState.remoteFocusSink = null; SpaceState.remoteBlurSink = null
        set("tắt")
    }

    /** REMOTE touchpad helpers */
    fun pad(action: Int, dx: Float, dy: Float) = send(Msg.Pad(action, dx, dy))
    val isRemote get() = role == Role.REMOTE && connected
}
