package com.siso.glasslauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * The user's own wallpaper photos. Every photo is stored already cropped to 1920×1080,
 * so showing it later is one cheap decode.
 */
object PhotoStore {
    private const val OUT_W = 1920
    private const val OUT_H = 1080
    private const val MAX_BYTES = 40 * 1024 * 1024

    private fun dir(ctx: Context): File = File(ctx.filesDir, "wallpapers").apply { mkdirs() }

    fun file(ctx: Context, name: String): File = File(dir(ctx), name)

    fun list(ctx: Context): List<String> =
        dir(ctx).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jpg") }
            ?.sortedBy { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()

    fun load(ctx: Context, name: String): Bitmap? =
        try {
            BitmapFactory.decodeFile(file(ctx, name).absolutePath)
        } catch (e: Throwable) {
            null
        }

    fun thumb(ctx: Context, name: String): Bitmap? =
        try {
            BitmapFactory.decodeFile(
                file(ctx, name).absolutePath,
                BitmapFactory.Options().apply { inSampleSize = 8 },
            )
        } catch (e: Throwable) {
            null
        }

    fun delete(ctx: Context, name: String) {
        runCatching { file(ctx, name).delete() }
    }

    /** Any image → upright, center-cropped 1920×1080 JPEG in our folder. Returns the new file name, or null. */
    fun importBytes(ctx: Context, bytes: ByteArray): String? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= OUT_W && bounds.outHeight / (sample * 2) >= OUT_H) sample *= 2
            val raw = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            // Phone cameras often store the picture sideways and note the rotation in EXIF.
            val rotation = try {
                when (
                    ExifInterface(ByteArrayInputStream(bytes))
                        .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } catch (e: Throwable) {
                0f
            }
            val upright = if (rotation != 0f) {
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rotation) }, true)
            } else {
                raw
            }
            val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
            val scale = max(OUT_W / upright.width.toFloat(), OUT_H / upright.height.toFloat())
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate((OUT_W - upright.width * scale) / 2f, (OUT_H - upright.height * scale) / 2f)
            Canvas(out).drawBitmap(upright, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            val name = "photo_" + System.currentTimeMillis() + ".jpg"
            FileOutputStream(file(ctx, name)).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            return name
        } catch (e: Throwable) {
            return null
        }
    }

    fun importFile(ctx: Context, src: File): String? =
        try {
            if (src.length() > MAX_BYTES) null else importBytes(ctx, src.readBytes())
        } catch (e: Throwable) {
            null
        }

    fun importUrl(ctx: Context, address: String): String? {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(address.trim())
            if (url.protocol != "http" && url.protocol != "https") return null
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "GlassLauncher")
            }
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { readLimited(it, MAX_BYTES) } ?: return null
            return importBytes(ctx, bytes)
        } catch (e: Throwable) {
            return null
        } finally {
            conn?.disconnect()
        }
    }

    internal fun readLimited(input: InputStream, limit: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) return null
        }
        return out.toByteArray()
    }

    /** Image files on USB drives / SD cards (not the internal storage), newest first. */
    fun scanExternal(ctx: Context): List<File> {
        val roots = ctx.getExternalFilesDirs(null)
            .filterNotNull()
            .drop(1) // the first entry is the built-in storage
            .mapNotNull { f ->
                val p = f.absolutePath
                val i = p.indexOf("/Android/")
                if (i > 0) File(p.substring(0, i)) else null
            }
        val found = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (found.size >= 120) return
            val kids = try { d.listFiles() } catch (e: Throwable) { null } ?: return
            for (k in kids) {
                if (found.size >= 120) return
                val n = k.name
                if (n.startsWith(".")) continue
                if (k.isDirectory) {
                    if (depth < 4 && n != "Android") walk(k, depth + 1)
                } else {
                    val lower = n.lowercase()
                    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp")) {
                        found.add(k)
                    }
                }
            }
        }
        roots.forEach { walk(it, 0) }
        return found.sortedByDescending { it.lastModified() }
    }
}

/**
 * A tiny web page served from the TV while the "add from phone" screen is open.
 * The phone (same Wi-Fi) opens it by scanning the QR code and sends photos or an image link.
 * The address carries a random key, and the server stops when the screen closes.
 */
class PhotoServer(private val ctx: Context, private val onAdded: (String) -> Unit) {
    @Volatile
    private var server: ServerSocket? = null
    private val key = (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")

    /** Starts listening. Returns the address to open on the phone, or null when there is no network. */
    fun start(): String? {
        val ip = localIp() ?: return null
        val s = try { ServerSocket(0) } catch (e: Throwable) { return null }
        server = s
        thread(isDaemon = true, name = "photo-server") {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Throwable) { break }
                thread(isDaemon = true) { runCatching { handle(c) } }
            }
        }
        return "http://" + ip + ":" + s.localPort + "/?k=" + key
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun localIp(): String? =
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Throwable) {
            null
        }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 " + code + " OK\r\nContent-Type: " + type + "\r\nContent-Length: " + body.size +
            "\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            s.soTimeout = 30_000
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()
            val request = readLine(input) ?: return
            var length = 0L
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.lowercase().startsWith("content-length:")) {
                    length = line.substring(15).trim().toLongOrNull() ?: 0L
                }
            }
            val parts = request.split(" ")
            val method = parts.getOrNull(0) ?: ""
            val path = parts.getOrNull(1) ?: "/"
            val authorized = path.contains("k=" + key)
            when {
                !authorized -> respond(out, 403, "text/plain; charset=utf-8", "forbidden".toByteArray())
                method == "GET" -> respond(out, 200, "text/html; charset=utf-8", page().toByteArray(Charsets.UTF_8))
                method == "POST" && length > 0 && length <= 40L * 1024 * 1024 -> {
                    val body = ByteArray(length.toInt())
                    var read = 0
                    while (read < body.size) {
                        val n = input.read(body, read, body.size - read)
                        if (n < 0) break
                        read += n
                    }
                    val name = if (read < body.size) null
                    else if (path.startsWith("/url")) PhotoStore.importUrl(ctx, String(body, Charsets.UTF_8))
                    else PhotoStore.importBytes(ctx, body)
                    if (name != null) onAdded(name)
                    respond(out, if (name != null) 200 else 400, "text/plain; charset=utf-8", (if (name != null) "ok" else "fail").toByteArray())
                }
                else -> respond(out, 400, "text/plain; charset=utf-8", "bad request".toByteArray())
            }
        }
    }

    private fun page(): String = """
<!doctype html><html lang="he" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>טפטים לטלוויזיה</title>
<style>
body{margin:0;padding:24px;font-family:system-ui,sans-serif;background:#10131f;color:#fff}
h1{font-size:24px;margin:0 0 6px}p{color:#c9cfdd;margin:0 0 20px;line-height:1.5}
.card{background:#1c2133;border:1px solid #39415c;border-radius:20px;padding:20px;margin-bottom:16px}
label{display:block;font-weight:600;margin-bottom:10px}
input[type=url]{width:100%;box-sizing:border-box;padding:14px;border-radius:12px;border:1px solid #4a5475;background:#10131f;color:#fff;font-size:16px;direction:ltr}
button,.pick{display:block;width:100%;box-sizing:border-box;margin-top:12px;padding:16px;border:0;border-radius:14px;background:#fff;color:#10131f;font-size:17px;font-weight:700;text-align:center}
input[type=file]{display:none}#st{min-height:24px;font-weight:600}
</style></head><body>
<h1>טפטים לטלוויזיה</h1>
<p>בחרו תמונות מהטלפון או הדביקו קישור לתמונה. הן יופיעו מיד בטלוויזיה.</p>
<div class="card"><label for="f">תמונות מהטלפון</label>
<label class="pick" for="f">בחירת תמונות</label><input id="f" type="file" accept="image/*" multiple></div>
<div class="card"><label for="u">קישור לתמונה</label>
<input id="u" type="url" placeholder="https://"><button id="b" type="button">הוספה מהקישור</button></div>
<div id="st" role="status"></div>
<script>
var q = location.search, st = document.getElementById('st');
function say(t){ st.textContent = t; }
document.getElementById('f').addEventListener('change', async function(e){
  var files = Array.from(e.target.files), ok = 0;
  for (var i = 0; i < files.length; i++) {
    say('שולח ' + (i + 1) + ' מתוך ' + files.length + '…');
    try { var r = await fetch('/upload' + q, {method:'POST', body: files[i]}); if (r.ok) ok++; } catch (err) {}
  }
  say('נוספו ' + ok + ' מתוך ' + files.length + ' תמונות.');
  e.target.value = '';
});
document.getElementById('b').addEventListener('click', async function(){
  var u = document.getElementById('u').value.trim();
  if (!u) { say('הדביקו קישור לתמונה.'); return; }
  say('מוריד את התמונה…');
  try { var r = await fetch('/url' + q, {method:'POST', body: u}); say(r.ok ? 'התמונה נוספה.' : 'לא הצלחתי להוסיף את התמונה מהקישור הזה.'); }
  catch (err) { say('אין חיבור לטלוויזיה.'); }
});
</script></body></html>
""".trimIndent()
}

// ───────────────────────── Settings UI pieces ─────────────────────────

/** One wallpaper thumbnail in a row of three. */
@Composable
fun RowScope.WallThumb(
    name: String,
    selected: Boolean,
    brush: Brush? = null,
    image: ImageBitmap? = null,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .liquidRing(focused, 10.dp, 3.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .tvClickable(true, { focused = it }, onClick)
                .clip(shape)
                .background(brush ?: Brush.verticalGradient(listOf(Color(0xFF2A3146), Color(0xFF151927))))
                .glassOverlay(shape)
                .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    GlyphIcon(Glyph.Check, 14.dp)
                }
            }
        }
        T(name, 10.sp, FontWeight.Medium, modifier = Modifier.padding(top = 3.dp, bottom = 4.dp))
    }
}

/** Fills the empty cells of a last, shorter thumbnail row. */
@Composable
fun RowScope.ThumbGap(count: Int) {
    repeat(count) { Spacer(Modifier.weight(1f)) }
}

/** QR code + address of the phone upload page. The server lives exactly as long as this is on screen. */
@Composable
fun PhoneUploadBlock(vm: HomeViewModel) {
    val ctx = LocalContext.current.applicationContext
    var address by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var added by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val server = PhotoServer(ctx) { name ->
            main.post {
                added += 1
                vm.reloadPhotos()
                vm.usePhoto(name)
            }
        }
        thread(isDaemon = true) {
            val a = server.start()
            main.post {
                address = a
                failed = a == null
            }
        }
        onDispose { server.stop() }
    }
    val a = address
    val matrix = remember(a) {
        if (a == null) null else runCatching { QRCodeWriter().encode(a, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull()
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            failed -> T("אין חיבור לרשת. חבר את הסטרימר ל-Wi-Fi ונסה שוב.", 13.sp, maxLines = 3)
            matrix == null -> T("מכין קוד…", 13.sp, color = Color.White.copy(alpha = 0.8f))
            else -> {
                T("סרוק את הקוד בטלפון (באותה רשת Wi-Fi)", 13.sp, FontWeight.Medium, maxLines = 2)
                androidx.compose.foundation.Canvas(
                    Modifier
                        .size(170.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                ) {
                    val n = matrix.width
                    val cell = size.width / n
                    for (y in 0 until n) {
                        for (x in 0 until n) {
                            if (matrix.get(x, y)) {
                                drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                            }
                        }
                    }
                }
                Ltr { T(a ?: "", 11.sp, color = Color.White.copy(alpha = 0.85f)) }
                T(
                    if (added == 0) "בדף שנפתח: בחר תמונות או הדבק קישור לתמונה" else "נוספו $added תמונות",
                    12.sp,
                    FontWeight.Medium,
                    maxLines = 2,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}
