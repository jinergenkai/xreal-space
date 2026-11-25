package vn.jiner.xrealspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import vn.jiner.xrealspace.core.DocNav
import vn.jiner.xrealspace.core.Epub
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/** A document open in a panel: "doc:<content uri>". Position is remembered per document. */
sealed class DocView(val uri: Uri) {
    abstract val label: String
    abstract fun step(dir: Int)
    abstract fun goPage(page1: Int)
    abstract fun close()

    companion object {
        const val PREFIX = "doc:"
        fun uriOf(url: String): Uri? = if (url.startsWith(PREFIX)) Uri.parse(url.removePrefix(PREFIX)) else null

        fun displayName(ctx: Context, uri: Uri): String = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "Tài liệu"
        } catch (_: Exception) { uri.lastPathSegment ?: "Tài liệu" }

        fun isEpub(ctx: Context, uri: Uri) =
            ctx.contentResolver.getType(uri)?.contains("epub") == true || displayName(ctx, uri).lowercase().endsWith(".epub")
    }
}

/**
 * PDF with Android's own PdfRenderer (no library). Pages are rendered 1280 px wide in slices that fit the panel,
 * on a background thread (PdfRenderer allows one open page at a time, so one thread).
 */
class PdfDoc(private val ctx: Context, uri: Uri, private val show: (Bitmap) -> Unit) : DocView(uri) {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var fd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val aspects = HashMap<Int, Double>()
    private var nav: DocNav? = null
    @Volatile override var label = "Đang mở…"; private set

    init {
        io.execute {
            try {
                fd = ctx.contentResolver.openFileDescriptor(uri, "r")
                val r = PdfRenderer(fd!!); renderer = r
                val n = DocNav({ aspectOf(it) }, r.pageCount)
                DocPositions.get(uri.toString())?.let { (p, s) -> n.go(p, s) }
                nav = n
                draw()
            } catch (e: Exception) {
                label = "Không mở được"
                main.post { SpaceState.notify("Tài liệu", "Không mở được PDF: ${e.message}", Colors.RED, 4.0) }
            }
        }
    }

    /** height / width of a page (opens it briefly; cached) — called on the io thread only */
    private fun aspectOf(i: Int): Double = aspects.getOrPut(i) {
        renderer?.openPage(i)?.use { it.height.toDouble() / it.width } ?: 1.414
    }

    private fun draw() {
        val r = renderer ?: return; val n = nav ?: return
        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        r.openPage(n.page).use { pg ->
            val scale = 1280f / pg.width
            val m = Matrix().apply { postScale(scale, scale); postTranslate(0f, (-n.sliceTop() * pg.height * scale).toFloat()) }
            pg.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        label = n.label()
        DocPositions.put(uri.toString(), n.page, n.slice)
        main.post { show(bmp) }
    }

    override fun step(dir: Int) = io.execute {
        val n = nav ?: return@execute
        val moved = if (dir > 0) n.next() else n.prev()
        if (moved) draw() else main.post { SpaceState.notify("Tài liệu", if (dir > 0) "Hết tài liệu" else "Đang ở đầu", Colors.GREY, 1.5) }
    }

    override fun goPage(page1: Int) = io.execute { nav?.let { it.go(page1 - 1); draw() } }

    override fun close() = io.execute { try { renderer?.close(); fd?.close() } catch (_: Exception) {} }
}

/**
 * EPUB: unzipped once into the cache, chapters shown in the panel's WebView. "Next" scrolls one screen,
 * then moves to the next chapter.
 */
class EpubDoc(private val ctx: Context, uri: Uri, private val web: android.webkit.WebView) : DocView(uri) {
    private var chapters = listOf<File>()
    private var chapter = 0
    private var title = "EPUB"
    private val main = Handler(Looper.getMainLooper())
    /** after loading a chapter: scroll to this fraction of the page (resume / going back) */
    private var pendingScroll = 0.0
    override val label get() = if (chapters.isEmpty()) "Đang mở…" else "$title · chương ${chapter + 1}/${chapters.size}"

    init {
        web.settings.allowFileAccess = true
        Thread {
            try {
                val dir = File(ctx.cacheDir, "epub/" + Integer.toHexString(uri.toString().hashCode()))
                if (!File(dir, "META-INF/container.xml").exists()) unzip(dir)
                val opfPath = Epub.opfPath(File(dir, "META-INF/container.xml").readText()) ?: error("no OPF")
                val opf = File(dir, opfPath)
                val txt = opf.readText()
                title = Epub.title(txt)?.take(30) ?: "EPUB"
                chapters = Epub.spine(txt).map { File(opf.parentFile, it) }.filter { it.exists() }
                if (chapters.isEmpty()) error("no chapters")
                DocPositions.get(uri.toString())?.let { (c, s) -> chapter = c.coerceIn(0, chapters.size - 1); pendingScroll = s / 1000.0 }
                main.post { load() }
            } catch (e: Exception) {
                main.post { SpaceState.notify("Tài liệu", "Không mở được EPUB: ${e.message}", Colors.RED, 4.0) }
            }
        }.start()
    }

    private fun unzip(dir: File) {
        dir.mkdirs()
        val root = dir.canonicalPath + File.separator
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            ZipInputStream(input).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val out = File(dir, e.name)
                    if (!out.canonicalPath.startsWith(root)) continue       // zip-slip guard
                    if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
            }
        }
    }

    private fun load() {
        web.loadUrl(Uri.fromFile(chapters[chapter]).toString())
        DocPositions.put(uri.toString(), chapter, (pendingScroll * 1000).toInt())
    }

    /** WebView finished a chapter: apply readable dark style + pending scroll */
    fun onChapterLoaded() {
        val css = "html,body{background:#000!important;color:#e6e6e6!important;font-size:30px!important;line-height:1.55!important;" +
            "margin:0 24px!important;max-width:none!important}a{color:#7cc7ff!important}img{max-width:100%!important;height:auto!important}"
        web.evaluateJavascript("(function(){var s=document.createElement('style');s.textContent=${org.json.JSONObject.quote(css)};" +
            "document.head&&document.head.appendChild(s);setTimeout(function(){scrollTo(0,(document.body.scrollHeight-innerHeight)*$pendingScroll);},60);})()", null)
        pendingScroll = 0.0
    }

    override fun step(dir: Int) {
        if (chapters.isEmpty()) return
        web.evaluateJavascript("(function(d){var y=scrollY;scrollBy(0,d*innerHeight*0.85);" +
            "var max=Math.max(1,document.body.scrollHeight-innerHeight);return (scrollY==y?'edge':'ok')+':'+Math.round(scrollY/max*1000);})($dir)") { r ->
            val v = r?.trim('"') ?: return@evaluateJavascript
            val (state, frac) = v.split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
            if (state == "ok") { DocPositions.put(uri.toString(), chapter, frac); return@evaluateJavascript }
            val nc = chapter + dir
            if (nc !in chapters.indices) { SpaceState.notify("Tài liệu", if (dir > 0) "Hết sách" else "Đang ở đầu", Colors.GREY, 1.5); return@evaluateJavascript }
            chapter = nc; pendingScroll = if (dir < 0) 1.0 else 0.0; load()
            SpaceState.notify(title, "Chương ${chapter + 1}/${chapters.size}", Colors.CYAN, 1.5)
        }
    }

    override fun goPage(page1: Int) {
        if (chapters.isEmpty()) return
        chapter = (page1 - 1).coerceIn(0, chapters.size - 1); pendingScroll = 0.0; load()
    }

    override fun close() {}
}

/** Last position per document ("page,slice" for PDF, "chapter,scroll‰" for EPUB). */
object DocPositions {
    private const val PREFS = "doc_positions"
    private var ctx: Context? = null
    fun init(c: Context) { ctx = c.applicationContext }
    fun get(key: String): Pair<Int, Int>? = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(key, null)
        ?.split(',')?.let { if (it.size == 2) (it[0].toIntOrNull() ?: 0) to (it[1].toIntOrNull() ?: 0) else null }
    fun put(key: String, a: Int, b: Int) { ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(key, "$a,$b")?.apply() }
    /** most recently opened document, for "đọc tiếp" */
    var lastDoc: String?
        get() = ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString("__last", null)
        set(v) { ctx?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString("__last", v)?.apply() }
}
