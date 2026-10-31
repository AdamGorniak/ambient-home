package pl.ambienthome.tv

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** Explicit, temporary LAN upload. Random capability URL, no file browsing or persistent service. */
class PhotoTransferServer(private val context: Context, private val album: WallAlbum, private val report: String? = null, private val imported: (WallAlbum.Photo) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    val token: String = UUID.randomUUID().toString().replace("-", "")
    @Volatile private var running = true
    @Volatile private var active: Socket? = null
    init { Thread({
        while (running) try {
            server.accept().use { socket -> active = socket; socket.soTimeout = 30_000; handle(socket) }
        } catch (_: Exception) { /* Closing the dialog cancels accept/upload. */ }
        finally { active = null }
    }, "Ambient-photo-transfer").apply { isDaemon = true; start() } }

    private fun line(input: BufferedInputStream): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val byte = input.read()
            if (byte < 0 || byte == 10) break
            require(bytes.size < 8192)
            if (byte != 13) bytes.add(byte.toByte())
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }
    private fun respond(socket: Socket, status: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write(("HTTP/1.1 $status\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }
    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val first = line(input).split(' ')
        if (first.size != 3) return
        val headers = mutableMapOf<String,String>()
        var total = 0
        while (true) { val h = line(input); if (h.isEmpty()) break; total += h.length; require(total < 16384); val colon = h.indexOf(':'); if (colon > 0) headers[h.substring(0, colon).lowercase()] = h.substring(colon + 1).trim() }
        val target = first[1]
        if (target != "/?token=$token" && target != "/upload?token=$token" && !(report != null && target == "/report?token=$token")) { respond(socket, "403 Forbidden", "text/plain", "Nieprawidłowy adres transferu."); return }
        val origin = headers["origin"]
        if (origin != null && origin != "http://${headers["host"]}") { respond(socket, "403 Forbidden", "text/plain", "Niedozwolone źródło."); return }
        if (report != null) {
            if (first[0] == "GET" && target.startsWith("/report?")) respond(socket, "200 OK", "application/json", report)
            else if (first[0] == "GET" && target.startsWith("/?")) respond(socket, "200 OK", "text/html", "<!doctype html><html lang=pl><meta charset=utf-8><title>Ambient · raport</title><h1>Raport odzyskiwania</h1><p>Zapisz na swoim komputerze przed zmianami na TV.</p><a download=Ambient-debloat-report.json href='/report?token=$token'>Pobierz raport JSON z poleceniami powrotu</a></html>")
            else respond(socket, "405 Method Not Allowed", "text/plain", "To okno udostępnia tylko raport.")
            return
        }
        if (first[0] == "GET" && target.startsWith("/?")) { respond(socket, "200 OK", "text/html", page); return }
        val size = headers["content-length"]?.toLongOrNull() ?: -1
        if (first[0] != "POST" || !target.startsWith("/upload?") || size !in 1..(40L * 1024 * 1024)) { respond(socket, "400 Bad Request", "text/plain", "Wybierz zdjęcie do 40 MB."); return }
        val file = File.createTempFile("upload", ".tmp", context.cacheDir)
        try {
            file.outputStream().use { output ->
                var remaining = size
                val buffer = ByteArray(16384)
                while (remaining > 0 && running) { val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt()); require(n > 0); output.write(buffer, 0, n); remaining -= n }
                require(remaining == 0L && running)
            }
            val photo = album.importPhoto(Uri.fromFile(file))
            imported(photo)
            respond(socket, "200 OK", "text/plain", "Dodano fotografię do Ambient.")
        } catch (_: Exception) { if (running) respond(socket, "400 Bad Request", "text/plain", "Nie można dodać zdjęcia. Sprawdź format, rozmiar i miejsce w albumie.") }
        finally { file.delete() }
    }
    override fun close() { running = false; try { server.close() } catch (_: Exception) {}; try { active?.close() } catch (_: Exception) {} }
    private val page get() = """<!doctype html><html lang="pl"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Ambient · zdjęcia</title><style>body{font:18px system-ui;background:#101b29;color:#edf4fa;max-width:650px;margin:8vh auto;padding:24px}input,button{font:inherit;margin:16px 0;padding:12px}button{cursor:pointer}p{line-height:1.5}</style><h1>Twoje zdjęcia na telewizorze</h1><p>Wybierz fotografie. Transfer odbywa się bezpośrednio do Ambient w Twojej sieci. Do 40 MB na plik, do 100 zdjęć w albumie. Zostaw okno transferu otwarte na TV.</p><input id="files" type="file" accept="image/*" multiple><br><button id="send">Wyślij zdjęcia</button><p id="status" role="status"></p><script>document.getElementById('send').onclick=async()=>{const b=document.getElementById('send'),s=document.getElementById('status');b.disabled=true;let n=0;try{for(const f of document.getElementById('files').files){if(f.size>41943040)throw Error('Plik przekracza 40 MB');s.textContent='Wysyłanie: '+f.name;const r=await fetch('/upload?token=$token',{method:'POST',body:f});const t=await r.text();if(!r.ok)throw Error(t);n++;}s.textContent='Dodano zdjęć: '+n;}catch(e){s.textContent=e.message;}finally{b.disabled=false;}};</script></html>"""
}
