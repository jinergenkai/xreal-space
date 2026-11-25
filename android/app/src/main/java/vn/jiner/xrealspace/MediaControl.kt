package vn.jiner.xrealspace

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import vn.jiner.xrealspace.core.MediaAction

/**
 * "tạm dừng", "tua 30 giây", "bài tiếp"… First the video in the glasses (the page you look at / the cinema),
 * otherwise the phone app that is playing (Spotify, YouTube, Zing MP3…) through Android media sessions.
 * Reading other apps' sessions uses the notification access the user already granted.
 */
object MediaControl {
    fun act(ctx: Context, action: MediaAction, seconds: Int) {
        val space = SpaceHost.space
        if (space == null) { phone(ctx, action, seconds); return }
        space.mediaInPage(action, seconds) { result ->
            when (result) {
                "ok" -> SpaceState.notify(label(action, seconds), "video trên kính", Colors.CYAN, 1.5)
                "unsupported" -> phone(ctx, action, seconds, fromWeb = true)
                else -> phone(ctx, action, seconds)
            }
        }
    }

    private fun phone(ctx: Context, action: MediaAction, seconds: Int, fromWeb: Boolean = false) {
        val c = activeController(ctx)
        if (c == null) {
            SpaceState.notify("Điều khiển phát", if (fromWeb) "Trang web không có nút này — bấm trên trang"
                else if (!NotifListener.isEnabled(ctx)) "Cấp \"Đọc thông báo ĐT\" để điều khiển app nhạc" else "Không có gì đang phát", Colors.AMBER, 3.0)
            return
        }
        val t = c.transportControls
        when (action) {
            MediaAction.PLAY -> t.play()
            MediaAction.PAUSE -> t.pause()
            MediaAction.NEXT -> t.skipToNext()
            MediaAction.PREV -> t.skipToPrevious()
            MediaAction.SEEK -> {
                val pos = c.playbackState?.position ?: 0L
                t.seekTo((pos + seconds * 1000L).coerceAtLeast(0))
            }
        }
        val app = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Exception) { c.packageName }
        SpaceState.notify(label(action, seconds), app, Colors.CYAN, 1.5)
    }

    /** the session that is playing, else the most recent one */
    private fun activeController(ctx: Context): MediaController? = try {
        val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = m.getActiveSessions(ComponentName(ctx, NotifListener::class.java))
            .filter { it.packageName != ctx.packageName }
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    } catch (e: SecurityException) { null }

    private fun label(a: MediaAction, s: Int) = when (a) {
        MediaAction.PLAY -> "▶ Phát"; MediaAction.PAUSE -> "⏸ Tạm dừng"
        MediaAction.NEXT -> "⏭ Tiếp"; MediaAction.PREV -> "⏮ Trước"
        MediaAction.SEEK -> if (s < 0) "⏪ Lùi ${-s} giây" else "⏩ Tua $s giây"
    }
}
