package vn.jiner.xrealspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import vn.jiner.xrealspace.core.ImuTracker
import vn.jiner.xrealspace.core.Protocol

/**
 * Talks to the XREAL glasses IMU over raw USB (Android USB host API, no root, no vendor SDK).
 * Flow: find device (VID 0x3318) -> ask permission -> claim the IMU interface -> send "start stream"
 * -> background thread reads 64-byte interrupt reports and feeds the tracker.
 */
class GlassesUsb(private val ctx: Context, private val onState: () -> Unit) {
    companion object { const val ACTION_PERMISSION = "vn.jiner.xrealspace.USB_PERMISSION" }

    private val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    @Volatile var status = "Chưa kết nối"; private set
    @Volatile var packets = 0L; private set
    @Volatile var parsed = 0L; private set
    @Volatile var otherPackets = 0L; private set
    @Volatile var lastOther = ""; private set
    @Volatile var lastError = ""; private set
    var device: UsbDevice? = null; private set
    val connected get() = reader != null

    private var conn: UsbDeviceConnection? = null
    private var intf: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null
    @Volatile private var reader: Thread? = null
    @Volatile private var running = false
    private var tracker: ImuTracker? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_PERMISSION -> {
                    val ok = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    SpaceState.log("USB permission granted=$ok")
                    if (ok) findGlasses()?.let { open(it) } else setStatus("Bị từ chối quyền USB")
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val d = deviceExtra(i)
                    if (d != null && d.deviceName == device?.deviceName) { SpaceState.log("Glasses detached"); close(); setStatus("Kính đã rút ra") }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> { SpaceState.log("USB attached"); connect() }
            }
        }
    }

    fun register() {
        val f = IntentFilter().apply {
            addAction(ACTION_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(receiver, f)
    }

    fun unregister() { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {} }

    @Suppress("DEPRECATION")
    private fun deviceExtra(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    fun findGlasses(): UsbDevice? = um.deviceList.values.firstOrNull { it.vendorId == Protocol.VENDOR_ID && Protocol.MODELS.containsKey(it.productId) }
        ?: um.deviceList.values.firstOrNull { it.vendorId == Protocol.VENDOR_ID }

    /** Find the glasses and open them, asking for permission if needed. */
    fun connect(): Boolean {
        if (connected) return true
        val d = findGlasses()
        if (d == null) { setStatus("Không thấy kính trên USB"); return false }
        if (!um.hasPermission(d)) {
            setStatus("Đang xin quyền USB…")
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
            um.requestPermission(d, pi)
            return false
        }
        return open(d)
    }

    private fun pickInterface(d: UsbDevice): UsbInterface? {
        val all = (0 until d.interfaceCount).map { d.getInterface(it) }
        val want = Protocol.MODELS[d.productId]?.imuInterface ?: 3
        fun hasIntIn(i: UsbInterface) = (0 until i.endpointCount).any {
            val e = i.getEndpoint(it); e.type == UsbConstants.USB_ENDPOINT_XFER_INT && e.direction == UsbConstants.USB_DIR_IN
        }
        return all.firstOrNull { it.id == want && hasIntIn(it) }
            ?: all.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID && it.id == want }
            ?: all.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_HID && hasIntIn(it) }
    }

    private fun open(d: UsbDevice): Boolean {
        close()
        val model = Protocol.MODELS[d.productId]
        val i = pickInterface(d)
        if (i == null) { fail("Không tìm thấy interface IMU (PID 0x%04x)".format(d.productId)); return false }
        val c = um.openDevice(d)
        if (c == null) { fail("openDevice() trả về null"); return false }
        if (!c.claimInterface(i, true)) { c.close(); fail("claimInterface(${i.id}) thất bại — hệ thống có thể đang giữ kính"); return false }
        var inEp: UsbEndpoint? = null; var outEp: UsbEndpoint? = null
        for (k in 0 until i.endpointCount) {
            val e = i.getEndpoint(k)
            if (e.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = e
            if (e.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = e
        }
        if (inEp == null) { c.releaseInterface(i); c.close(); fail("Interface ${i.id} không có endpoint IN"); return false }
        device = d; conn = c; intf = i; epIn = inEp; epOut = outEp
        SpaceState.useTracker(SpaceState.ImuSource.GLASSES)
        tracker = SpaceState.tracker
        val ok = write(Protocol.imuStream(true))
        SpaceState.log("Opened ${model?.name ?: "XREAL"} pid=0x%04x intf=%d in=0x%02x(%d) out=%s, start cmd ok=%s".format(
            d.productId, i.id, inEp.address, inEp.maxPacketSize, outEp?.let { "0x%02x".format(it.address) } ?: "none(ctrl)", ok))
        packets = 0; parsed = 0; otherPackets = 0
        running = true
        reader = Thread({ readLoop() }, "xreal-imu").apply { priority = Thread.MAX_PRIORITY; start() }
        setStatus("Đã kết nối ${model?.name ?: "XREAL"}")
        return true
    }

    private fun write(payload: ByteArray): Boolean {
        val c = conn ?: return false
        val out = epOut
        val n = if (out != null) {
            val rep = Protocol.report(payload, maxOf(out.maxPacketSize, Protocol.REPORT_SIZE).coerceAtMost(64))
            c.bulkTransfer(out, rep, rep.size, 500)
        } else {
            // HID SET_REPORT (output, report id 0) on the control pipe
            val rep = Protocol.report(payload)
            c.controlTransfer(0x21, 0x09, 0x0200, intf?.id ?: 0, rep, rep.size, 500)
        }
        if (n < 0) SpaceState.log("USB write failed ($n)")
        return n >= 0
    }

    private fun readLoop() {
        val c = conn ?: return
        val e = epIn ?: return
        val buf = ByteArray(maxOf(64, e.maxPacketSize))
        var errors = 0
        var lastRestart = System.currentTimeMillis()
        while (running) {
            val n = c.bulkTransfer(e, buf, buf.size, 200)
            if (n <= 0) {
                if (!running) break
                errors++
                // no data for a while: re-send the start command once per 2 s
                if (System.currentTimeMillis() - lastRestart > 2000) { lastRestart = System.currentTimeMillis(); write(Protocol.imuStream(true)) }
                if (errors > 200 && parsed == 0L) { lastError = "Không đọc được dữ liệu IMU ($n)"; errors = 0 }
                continue
            }
            errors = 0
            packets++
            val s = Protocol.parseSample(buf, n)
            if (s != null) {
                parsed++
                tracker?.update(s.tsNs, s.gyroDps, s.accelG)
            } else {
                otherPackets++
                if (otherPackets % 50 == 1L) lastOther = buf.take(minOf(n, 12)).joinToString(" ") { "%02x".format(it) }
            }
        }
    }

    fun close() {
        running = false
        val c = conn
        if (c != null) {
            try { write(Protocol.imuStream(false)) } catch (_: Exception) {}
            try { intf?.let { c.releaseInterface(it) } } catch (_: Exception) {}
            c.close()
        }
        reader?.join(500)
        reader = null; conn = null; intf = null; epIn = null; epOut = null; device = null
    }

    private fun fail(msg: String) { lastError = msg; SpaceState.log(msg); setStatus(msg) }
    private fun setStatus(s: String) { status = s; onState() }

    /** Human-readable dump of every USB device: used by the diagnostics card on Beam Pro. */
    fun describeAll(): String {
        val sb = StringBuilder()
        if (um.deviceList.isEmpty()) sb.append("(không có thiết bị USB nào app nhìn thấy)\n")
        for (d in um.deviceList.values) {
            sb.append("• %s  VID 0x%04x PID 0x%04x  class %d  quyền=%s\n".format(
                d.productName ?: d.deviceName, d.vendorId, d.productId, d.deviceClass, um.hasPermission(d)))
            for (k in 0 until d.interfaceCount) {
                val i = d.getInterface(k)
                sb.append("   intf %d class %d/%d eps:".format(i.id, i.interfaceClass, i.interfaceSubclass))
                for (j in 0 until i.endpointCount) {
                    val e = i.getEndpoint(j)
                    val t = when (e.type) { UsbConstants.USB_ENDPOINT_XFER_INT -> "int"; UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"; UsbConstants.USB_ENDPOINT_XFER_ISOC -> "iso"; else -> "ctl" }
                    sb.append(" 0x%02x/%s/%s/%d".format(e.address, t, if (e.direction == UsbConstants.USB_DIR_IN) "in" else "out", e.maxPacketSize))
                }
                sb.append('\n')
            }
        }
        return sb.toString()
    }
}
