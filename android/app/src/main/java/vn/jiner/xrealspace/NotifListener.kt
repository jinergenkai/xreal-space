package vn.jiner.xrealspace

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import vn.jiner.xrealspace.core.NotifFeed
import vn.jiner.xrealspace.core.PhoneNotif
import vn.jiner.xrealspace.core.Speech

/**
 * Real phone notifications on the glasses. The user grants "Notification access" once (Settings); Android then
 * hands us every notification. Messaging apps (Zalo, Messenger, SMS, Telegram…) put a reply box (RemoteInput)
 * in their notification: we fill it with the user's spoken text, so replying works without opening the app.
 */
class NotifListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this; SpaceState.log("notification listener connected") }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return        // "3 new messages" summaries duplicate the real ones
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        val pn = PhoneNotif(sbn.key, sbn.packageName, app, title, text, reply != null, sbn.postTime)
        main.post {
            val pop = PhoneNotifs.feed.add(pn, ongoing = sbn.isOngoing)
            if (reply != null) PhoneNotifs.replyActions[sbn.key] = reply else PhoneNotifs.replyActions.remove(sbn.key)
            if (pop) PhoneNotifs.show(pn)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        main.post { PhoneNotifs.feed.remove(sbn.key); PhoneNotifs.replyActions.remove(sbn.key) }
    }

    companion object {
        @Volatile var instance: NotifListener? = null
        private val main = Handler(Looper.getMainLooper())

        fun isEnabled(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, NotifListener::class.java).flattenToString()
            return flat.split(':').any { it == me }
        }

        fun openSettings(ctx: Context) {
            ctx.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SpaceState.notify("Đọc thông báo", "Chọn XREAL Space → Cho phép", Colors.CYAN, 5.0)
        }
    }
}

/** Process-wide notification state used by voice commands and head gestures. */
object PhoneNotifs {
    val feed = NotifFeed("vn.jiner.xrealspace")
    val replyActions = HashMap<String, Notification.Action>()

    /** Pop a notification on the glasses. Nod = reply by voice, shake = dismiss. */
    fun show(n: PhoneNotif) {
        val st = SpaceState
        val (title, body) = NotifFeed.preview(n, moving = st.speedLocked)
        val hint = if (n.canReply && !st.speedLocked) "   (gật = trả lời bằng giọng · lắc = bỏ qua)" else ""
        st.notify(title, body + hint, Colors.AMBER, if (n.canReply) 10.0 else 6.0, actionable = n.canReply && !st.speedLocked)
        Speaker.say(Speech.notification(n))
        st.toastNotifKey = if (n.canReply) n.key else null
    }

    fun read() {
        if (NotifListener.instance == null) { HealthCheck.explain(vn.jiner.xrealspace.core.Health.Req.NOTIF_ACCESS); return }
        val list = feed.recent(6)
        SpaceState.showCard("Thông báo (${list.size})", NotifFeed.readout(list), 20.0)
        Speaker.say(if (list.isEmpty()) "Không có thông báo" else list.joinToString(". ") { Speech.notification(it) }, flush = true, force = true)
    }

    /** Fill the app's own reply box (RemoteInput) and send it. key = null -> newest message that can be replied to. */
    fun reply(ctx: Context, text: String, key: String? = null): Boolean {
        val n = (key?.let { k -> feed.recent(20).firstOrNull { it.key == k } }) ?: feed.latestRepliable()
        val action = n?.let { replyActions[it.key] }
        if (n == null || action == null) { SpaceState.notify("Trả lời", "Không có tin nhắn nào trả lời được", Colors.AMBER, 3.0); return false }
        if (SpaceState.speedLocked) { SpaceState.notify("Trả lời", "Đang di chuyển nhanh — dừng lại rồi trả lời", Colors.AMBER, 3.0); return false }
        return try {
            val inputs = action.remoteInputs ?: return false
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val intent = Intent()
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(ctx, 0, intent)
            SpaceState.notify("Đã gửi · ${n.app}", "${n.title}: \"$text\"", Colors.GREEN, 3.0)
            SpaceState.log("reply sent to ${n.pkg}")
            true
        } catch (e: Exception) {
            SpaceState.notify("Không gửi được", e.message ?: "", Colors.RED, 3.0); false
        }
    }

    fun mute(on: Boolean, app: String?) {
        val st = SpaceState
        if (app == null) {
            feed.doNotDisturb = on
            st.notify(if (on) "Im lặng" else "Bật thông báo", if (on) "Không hiện thông báo lên kính (vẫn đọc được bằng \"đọc thông báo\")" else "Thông báo hiện lại trên kính", Colors.CYAN, 3.0)
            return
        }
        val pkg = feed.muteByName(app, on)
        if (pkg == null) st.notify("Thông báo", "Chưa thấy thông báo nào của \"$app\"", Colors.AMBER, 3.0)
        else st.notify(if (on) "Đã tắt thông báo" else "Đã bật thông báo", app, Colors.CYAN, 2.5)
    }
}
