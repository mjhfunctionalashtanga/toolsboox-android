package com.toolsboox.ot

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebResourceResponse
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.toolsboox.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

/**
 * The faces the readers can set text in — bundled and imported — and the one place every reading
 * WebView fetches them from (Aa Type, Michael 2026-10-11).
 *
 * Before this, no Android WebView could draw anything but the device's own serif: the faces in
 * res/font only ever reached plain views (LedgerFonts), and the engine's setFontFaces/
 * setReadingFont were never called. Now every face answers at one https address,
 * [ORIGIN]/…, served from inside the app by [intercept] — the book reader, the in-pane article
 * reader, the standalone article reader and the mail pane all route it the same way. ".invalid"
 * can never resolve, so a WebView that somehow missed the hook asks nobody.
 *
 * Bundled (all SIL OFL 1.1 — see THIRD-PARTY-NOTICES.md and assets/fonts/OFL.txt): the iOS
 * reader's set (Literata, Vollkorn, Inter, Jost, Poppins — assets/fonts/) and the res/font faces
 * already in the app (Atkinson Hyperlegible Next ×3, Fast Sans, Fast Mono — served straight out
 * of res/font, so the APK carries one copy of each). Fast Serif is NOT offered here: its own
 * name table carries a Dalton Maag EULA that forbids modification (Fast Serif is a modified
 * Dalton Maag face), which is not a license we can re-serve under.
 *
 * Imported: any TTF/OTF/WOFF/WOFF2 picked with the system file picker ([importLauncher]), copied
 * into filesDir/fonts/ (EinkBro's custom-font idea; our code). Listed, renamed, removed here.
 */
object ReadingFonts {

    const val HOST = "fonts.ledger.invalid"
    const val ORIGIN = "https://$HOST"

    /** The CSS generic families, offered beside the faces. */
    val GENERICS = listOf("serif", "sans-serif", "monospace")

    /** One @font-face src: [path] under [ORIGIN] ("a/…" = assets/fonts, "r/…" = res/font). */
    data class FaceFile(val path: String, val weight: String)

    /** A bundled face. [id] mirrors the iOS ReadingFont rawValue; [cssName] its faceName
     *  ("LedgerLiterata"), so a stored choice means the same face on both platforms. */
    data class Face(val id: String, val label: String, val generic: String, val files: List<FaceFile>) {
        val cssName: String get() = "Ledger" + id.replaceFirstChar { it.uppercase() }
        val cssFamily: String get() = "'$cssName', $generic"
    }

    /** An imported face. [id] = "u:<file>", the value [ReadingType.customFontId] stores.
     *  [cssName] carries a checksum of the whole file name: "My.Font.ttf" and "My_Font.ttf" read
     *  the same once squashed to CSS-safe characters, and two faces under one name drew as one
     *  (review, Michael 2026-10-11). */
    data class UserFont(val id: String, val file: File, val label: String) {
        val cssName: String get() {
            val crc = java.util.zip.CRC32().apply { update(file.name.toByteArray(Charsets.UTF_8)) }.value
            return "LedgerU_" + file.name.replace(Regex("[^A-Za-z0-9]"), "_") + "_" + java.lang.Long.toHexString(crc)
        }
    }

    val BUNDLED = listOf(
        // Variable faces declare their whole weight axis, so the weight dial is a real weight.
        Face("literata", "Literata", "serif", listOf(FaceFile("a/Literata.ttf", "200 900"))),
        Face("vollkorn", "Vollkorn", "serif", listOf(FaceFile("a/Vollkorn.ttf", "400"))),
        Face("inter", "Inter", "sans-serif", listOf(FaceFile("a/Inter.ttf", "100 900"))),
        Face("jost", "Jost", "sans-serif", listOf(FaceFile("a/Jost.ttf", "100 900"))),
        Face("poppins", "Poppins", "sans-serif", listOf(FaceFile("a/Poppins.ttf", "400"))),
        Face("atkinson", "Atkinson Hyperlegible", "sans-serif", listOf(
            FaceFile("r/atkinson_hyperlegible_regular.ttf", "400"),
            FaceFile("r/atkinson_hyperlegible_medium.ttf", "500"),
            FaceFile("r/atkinson_hyperlegible_bold.ttf", "700"),
        )),
        Face("fastSans", "Fast Sans", "sans-serif", listOf(FaceFile("r/fast_sans.ttf", "400"))),
        Face("fastMono", "Fast Mono", "monospace", listOf(FaceFile("r/fast_mono.ttf", "400"))),
    )

    /** res/font files by the name they're served under — a fixed map, no reflection. */
    private val RES = mapOf(
        "atkinson_hyperlegible_regular.ttf" to R.font.atkinson_hyperlegible_regular,
        "atkinson_hyperlegible_medium.ttf" to R.font.atkinson_hyperlegible_medium,
        "atkinson_hyperlegible_bold.ttf" to R.font.atkinson_hyperlegible_bold,
        "fast_sans.ttf" to R.font.fast_sans,
        "fast_mono.ttf" to R.font.fast_mono,
    )

    private val ASSETS: Set<String> =
        BUNDLED.flatMap { it.files }.map { it.path }.filter { it.startsWith("a/") }.map { it.removePrefix("a/") }.toSet()

    private const val USER_DIR = "fonts"
    private const val PREFS = "ledger_a11y"
    private const val KEY_NAMES = "type_font_names"
    private const val MAX_BYTES = 40L * 1024 * 1024
    /** A copy older than this that is still a ".part" died with the app mid-import. */
    private const val STALE_PART_MS = 10L * 60 * 1000

    /**
     * Imported faces answer only under this run's own key — u/<key>/<file> — never a bare
     * u/<file>. The in-pane article view also shows live web pages with JavaScript on, and the
     * font address answers every origin (CORS *, which @font-face needs); a page could otherwise
     * fetch u/<a guessed name> and learn or read what was imported. The key changes every launch
     * and only ever appears in CSS the app itself puts in a book or an article (review, Michael
     * 2026-10-11). The bundled faces are public files and stay at fixed addresses.
     */
    private val RUN_KEY: String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    private val USER_FILE = Regex("^[A-Za-z0-9._-]{1,100}\\.(ttf|otf|woff|woff2)$")

    /** What the picker offers. Pickers disagree about a font's type (Boox's says octet-stream),
     *  so octet-stream is in, and the bytes are checked on arrival ([sniffFormat]). */
    val PICK_TYPES = arrayOf(
        "font/*", "application/font-sfnt", "application/x-font-ttf", "application/x-font-otf",
        "application/vnd.ms-opentype", "application/font-woff", "application/octet-stream",
    )

    // ---- CSS ---------------------------------------------------------------------------------

    @Volatile private var facesCache: String? = null

    /** Every face as @font-face rules — what the book engine's setFontFaces and the article
     *  style block carry. Fonts load only when a rule actually uses them, so declaring them all
     *  costs nothing. Cached; an import or removal clears it. */
    fun fontFacesCss(context: Context): String =
        facesCache ?: (bundledFacesCss() + userFonts(context).joinToString("") { u ->
            faceRule(u.cssName, userUrl(u.file), "400", formatFor(u.file.name))
        }).also { facesCache = it }

    /** An imported face's address: this run's key, and the file's age, so a font removed and a
     *  different one imported under the same name is never answered from a cached copy. */
    private fun userUrl(f: File) = "$ORIGIN/u/$RUN_KEY/${f.name}?v=${f.lastModified()}"

    /** The bundled half of [fontFacesCss] — pure, for tests. */
    fun bundledFacesCss(): String = BUNDLED.joinToString("") { f ->
        f.files.joinToString("") { faceRule(f.cssName, "$ORIGIN/${it.path}", it.weight, formatFor(it.path)) }
    }

    /**
     * font-display: block, not swap — the faces are local, so the wait is a frame or two, and
     * swap would paint the page twice (fallback, then the face): two e-ink refreshes for one.
     */
    private fun faceRule(name: String, url: String, weight: String, format: String) =
        "@font-face { font-family: '$name'; src: url('$url') format('$format'); " +
            "font-weight: $weight; font-style: normal; font-display: block; }\n"

    /** The CSS font-family value for [t] — the imported face if it's still there, else [family];
     *  "" = leave the document's own. [context] null = imported faces can't be resolved. */
    fun familyCss(context: Context?, t: ReadingType): String {
        val custom = t.customFontId.takeIf { it.isNotBlank() && context != null }
            ?.let { userFont(context!!, it) }
        if (custom != null) return listOf("'${custom.cssName}'", familyCssFor(t.family))
            .filter { it.isNotBlank() }.joinToString(", ")
        return familyCssFor(t.family)
    }

    /** [ReadingType.family] → CSS: a generic as itself, a bundled id as its declared face. */
    fun familyCssFor(family: String): String = when {
        family.isBlank() -> ""
        family in GENERICS -> family
        else -> BUNDLED.firstOrNull { it.id == family }?.cssFamily ?: ""
    }

    /**
     * A face as an Android Typeface, so the Aa Type panel's list can show each face in itself
     * (Michael, 2026-10-11). Null when there is nothing to show it in — the document's own face,
     * or an imported WOFF, which Android's Typeface can't read (the WebViews still can).
     */
    fun previewTypeface(context: Context, family: String, customFontId: String): Typeface? {
        // Kept once built: the list draws every face each time it opens, and building Literata
        // (900 KB) and Inter on the main thread every time was a stall on a Boox (review,
        // Michael 2026-10-11). An imported face is keyed by its file's age, so a re-import shows.
        val key = if (customFontId.isNotBlank()) {
            val f = userFont(context, customFontId)?.file ?: return null
            "$customFontId@${f.lastModified()}"
        } else family
        previews[key]?.let { return it }
        return buildPreview(context, family, customFontId)?.also { previews[key] = it }
    }

    private val previews = java.util.concurrent.ConcurrentHashMap<String, Typeface>()

    private fun buildPreview(context: Context, family: String, customFontId: String): Typeface? = runCatching {
        if (customFontId.isNotBlank()) {
            return@runCatching userFont(context, customFontId)?.file
                ?.takeIf { formatFor(it.name) in setOf("truetype", "opentype") }
                ?.let { Typeface.createFromFile(it) }
        }
        when (family) {
            "serif" -> Typeface.SERIF
            "sans-serif" -> Typeface.SANS_SERIF
            "monospace" -> Typeface.MONOSPACE
            else -> BUNDLED.firstOrNull { it.id == family }?.files?.firstOrNull()?.path?.let { p ->
                when {
                    p.startsWith("a/") -> Typeface.createFromAsset(context.assets, "fonts/" + p.removePrefix("a/"))
                    p.startsWith("r/") -> RES[p.removePrefix("r/")]?.let { ResourcesCompat.getFont(context, it) }
                    else -> null
                }
            }
        }
    }.getOrNull()

    // ---- serving -----------------------------------------------------------------------------

    /**
     * Answer a WebView's request for a face, or null when the request isn't ours. Call it first
     * in every reading WebView's shouldInterceptRequest. Cross-origin by design (articles sit on
     * ArticleSanitizer's origin, books on ledger.reader), hence the CORS header @font-face needs.
     * Runs on the WebView's IO thread.
     */
    fun intercept(context: Context, url: Uri?): WebResourceResponse? {
        if (url?.host != HOST) return null
        val path = url.path.orEmpty().removePrefix("/")
        val stream: InputStream? = runCatching {
            when {
                path.startsWith("a/") -> path.removePrefix("a/").takeIf { it in ASSETS }
                    ?.let { context.assets.open("fonts/$it") }
                path.startsWith("r/") -> RES[path.removePrefix("r/")]?.let { context.resources.openRawResource(it) }
                path.startsWith("u/$RUN_KEY/") -> userFile(context, path.removePrefix("u/$RUN_KEY/"))?.inputStream()
                else -> null
            }
        }.getOrNull()
        return if (stream == null) {
            val headers = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store")
            WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers, ByteArrayInputStream(ByteArray(0)))
        } else {
            // Kept: every new book section asks again, and with font-display: block each re-read
            // of a 900 KB face is blank text a moment longer (review, Michael 2026-10-11). The
            // addresses never change content — a bundled file is fixed, an imported one is
            // versioned ([userUrl]) — so they can be kept for good.
            val headers = mapOf("Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "public, max-age=31536000, immutable")
            WebResourceResponse(mimeFor(path), null, 200, "OK", headers, stream)
        }
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "otf" -> "font/otf"; "woff" -> "font/woff"; "woff2" -> "font/woff2"; else -> "font/ttf"
    }

    private fun formatFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "otf" -> "opentype"; "woff" -> "woff"; "woff2" -> "woff2"; else -> "truetype"
    }

    // ---- imported fonts ----------------------------------------------------------------------

    fun userDir(context: Context) = File(context.filesDir, USER_DIR)

    /** The imported faces, by name. */
    fun userFonts(context: Context): List<UserFont> {
        val names = names(context)
        return userDir(context).listFiles { f -> f.isFile && USER_FILE.matches(f.name) }.orEmpty()
            .map { UserFont("u:${it.name}", it, names.optString(it.name).ifBlank { it.nameWithoutExtension }) }
            .sortedBy { it.label.lowercase() }
    }

    fun userFont(context: Context, id: String): UserFont? = userFonts(context).firstOrNull { it.id == id }

    private fun userFile(context: Context, name: String): File? =
        name.takeIf { USER_FILE.matches(it) }?.let { File(userDir(context), it) }?.takeIf { it.isFile }

    /**
     * Copy the picked font into filesDir/fonts/. Blocking — call off the main thread
     * ([importLauncher] does). The bytes decide what it is, not the picker's type or the name;
     * re-importing the same file returns the copy already there instead of a twin.
     */
    fun importFont(context: Context, uri: Uri): Result<UserFont> = runCatching {
        val cr = context.contentResolver
        val display = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font"
        val dir = userDir(context).apply { mkdirs() }
        // A copy the app died in the middle of is never listed and never finished — up to 40 MB
        // left in filesDir for good. Clear the old ones first; a fresh one may be another import
        // still running (review, Michael 2026-10-11).
        val now = System.currentTimeMillis()
        dir.listFiles { f -> f.name.startsWith(".import") && f.name.endsWith(".part") && now - f.lastModified() > STALE_PART_MS }
            ?.forEach { it.delete() }
        val part = File.createTempFile(".import", ".part", dir)   // never matches USER_FILE, so never listed
        try {
            cr.openInputStream(uri)?.use { input -> part.outputStream().use { copyCapped(input, it) } }
                ?: error("Couldn't open that file")
            val head = ByteArray(4)
            val got = part.inputStream().use { readFully(it, head) }
            val ext = sniffFormat(head.copyOf(got))
                ?: error("That isn't a font Ledgable can use (TTF, OTF, WOFF or WOFF2)")
            val stem = safeStem(display)
            var name = "$stem.$ext"
            var n = 2
            while (true) {
                val existing = File(dir, name)
                if (!existing.exists()) break
                if (sameBytes(existing, part)) { part.delete(); return@runCatching font(context, existing) }
                name = "$stem-${n++}.$ext"
            }
            val dest = File(dir, name)
            if (!part.renameTo(dest)) error("Couldn't keep that font")
            val label = runCatching { sfntFamilyName(dest) }.getOrNull()?.takeIf { it.isNotBlank() } ?: stem
            prefs(context).edit().putString(KEY_NAMES, names(context).put(name, label.take(80)).toString()).apply()
            facesCache = null
            font(context, dest)
        } finally {
            if (part.exists()) part.delete()
        }
    }

    /** Give an imported face a different name in the list. The file — and so every stored
     *  choice of it — stays put. */
    fun rename(context: Context, id: String, label: String): Boolean {
        val f = userFont(context, id) ?: return false
        val clean = label.trim().take(80).ifBlank { return false }
        prefs(context).edit().putString(KEY_NAMES, names(context).put(f.file.name, clean).toString()).apply()
        return true
    }

    /** Remove an imported face; any profile that used it falls back to its family. */
    fun remove(context: Context, id: String): Boolean {
        val f = userFont(context, id) ?: return false
        val gone = f.file.delete()
        val names = names(context).apply { remove(f.file.name) }
        prefs(context).edit().putString(KEY_NAMES, names.toString()).apply()
        ReadingTypes.forgetFont(context, id)
        facesCache = null
        return gone
    }

    /**
     * The system file picker for "Import font…", registered on [fragment] — call it while the
     * fragment is being constructed (a property initializer), as every ActivityResult launcher
     * must be. Launch with [PICK_TYPES]. [onResult] runs on the main thread.
     */
    fun importLauncher(fragment: Fragment, onResult: (Result<UserFont>) -> Unit): ActivityResultLauncher<Array<String>> =
        fragment.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = fragment.context?.applicationContext ?: return@registerForActivityResult
            fragment.lifecycleScope.launch {
                onResult(withContext(Dispatchers.IO) { importFont(ctx, uri) })
            }
        }

    private fun font(context: Context, f: File) =
        UserFont("u:${f.name}", f, names(context).optString(f.name).ifBlank { f.nameWithoutExtension })

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun names(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_NAMES, null) ?: "{}") }.getOrDefault(JSONObject())

    private fun copyCapped(input: InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) error("That font is too large (over 40 MB)")
            out.write(buf, 0, n)
        }
    }

    private fun readFully(input: InputStream, into: ByteArray): Int {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) break
            got += n
        }
        return got
    }

    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val p = x.read(); val q = y.read()
                    if (p != q) return false
                    if (p < 0) return true
                }
            }
        }
    }

    // ---- pure helpers (unit-tested) ----------------------------------------------------------

    /** The file extension a font's first four bytes say it is, or null if they say it isn't one.
     *  A collection (ttcf) is refused — CSS can't choose a face inside it. */
    fun sniffFormat(head: ByteArray): String? {
        if (head.size < 4) return null
        val tag = String(head, 0, 4, Charsets.ISO_8859_1)
        return when {
            head[0] == 0.toByte() && head[1] == 1.toByte() && head[2] == 0.toByte() && head[3] == 0.toByte() -> "ttf"
            tag == "true" -> "ttf"
            tag == "OTTO" -> "otf"
            tag == "wOFF" -> "woff"
            tag == "wOF2" -> "woff2"
            else -> null
        }
    }

    /** A safe file stem from a picked file's display name. */
    fun safeStem(display: String): String =
        display.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_', '.', '-').take(60).ifBlank { "font" }

    /** The family name inside a TTF/OTF (name id 16, else 1; Windows English first), or null —
     *  a WOFF is compressed, and its import keeps the file's own name instead. */
    fun sfntFamilyName(file: File): String? = RandomAccessFile(file, "r").use { raf ->
        sfntFamilyName { off, len ->
            if (off < 0 || len < 0 || off + len > raf.length()) null
            else ByteArray(len).also { raf.seek(off); raf.readFully(it) }
        }
    }

    internal fun sfntFamilyName(read: (Long, Int) -> ByteArray?): String? {
        val head = read(0, 12) ?: return null
        if (sniffFormat(head) !in setOf("ttf", "otf")) return null
        val numTables = u16(head, 4)
        if (numTables !in 1..512) return null
        val dir = read(12, 16 * numTables) ?: return null
        var nameOff = -1L
        var nameLen = 0L
        for (i in 0 until numTables) {
            if (String(dir, i * 16, 4, Charsets.ISO_8859_1) == "name") {
                nameOff = u32(dir, i * 16 + 8); nameLen = u32(dir, i * 16 + 12)
            }
        }
        if (nameOff < 0 || nameLen < 6 || nameLen > (1 shl 20)) return null
        val t = read(nameOff, nameLen.toInt()) ?: return null
        val count = u16(t, 2)
        val strings = u16(t, 4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until count) {
            val r = 6 + i * 12
            if (r + 12 > t.size) break
            val pid = u16(t, r); val eid = u16(t, r + 2); val lid = u16(t, r + 4)
            val nid = u16(t, r + 6); val len = u16(t, r + 8); val off = u16(t, r + 10)
            if (nid != 16 && nid != 1) continue
            val s = strings + off
            if (s + len > t.size) continue
            val text = when {
                pid == 3 || pid == 0 -> String(t, s, len, Charsets.UTF_16BE)
                pid == 1 && eid == 0 -> String(t, s, len, Charsets.ISO_8859_1)
                else -> continue
            }.trim()
            val rank = (if (nid == 16) 0 else 10) + when {
                pid == 3 && lid == 0x409 -> 0
                pid == 3 || pid == 0 -> 1
                else -> 2
            }
            if (text.isNotEmpty() && rank < bestRank) { best = text; bestRank = rank }
        }
        return best
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)
}
